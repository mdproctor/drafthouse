# Review Manifest — Design Spec
**Date:** 2026-06-01  
**Status:** Draft  
**Issues:** #26 (session continuity), #27 (Qhorus channel mapping)

---

## Problem

Spec review between two Claude agents (reviewer and implementer) currently happens
as unstructured conversation. State is buried in scroll history. The human must
read all rounds in sequence and mentally reconstruct the current picture. Points
cannot be precisely cited across rounds. There is no stable record.

---

## Goals

- Two agents can debate a spec in a structured, citation-based way
- The human can observe the full current state without scrolling or mental mapping
- The human can intervene at any point, step through iterations, rewind, and restart
- The file format mirrors a Qhorus channel message schema so the channel integration
  (#27) is a storage-layer swap, not a redesign

---

## Non-Goals (v1)

- Sub-agent spawning for focused analysis (→ #26)
- Per-round fresh context / threshold-based auto-reset (→ #26)
- Qhorus channel storage backend (→ #27)
- Provenance labelling (main agent vs sub-agent) (→ #26)

---

## Layered Architecture

```
Layer 3 — DraftHouse UI       live rendering, navigation controls, human intervention
Layer 2 — Agent Workflow      spec-reviewer and spec-implementer skills, round dispatch
Layer 1 — Review Protocol     debate.md + summary.md format, anchor scheme, state machine
```

Each layer is independently testable. Layer 1 can be exercised with a text editor
before any skills or DraftHouse rendering exist. Layer 2 can be tested with mock
manifests before Layer 3 exists.

---

## Layer 1 — Review Protocol

### Session Storage

Each review session is a standalone git repo:

```
~/.drafthouse/reviews/<session-id>/
  debate.md       — append-only LLM-to-LLM debate log
  summary.md      — rewritten-per-round human-readable view
```

Session ID: `drafthouse-<YYYYMMDD>-<6-char-hex>` derived from spec path + timestamp.

Each round is one git commit (both files). Git history provides:
- Time-travel: `git checkout <round-N-sha>`
- Diff between rounds: `git diff HEAD~1 HEAD`
- Restart from round N: `git checkout -b restart/<label> <round-N-sha>`

The session repo is isolated from the project repo — no review commits appear
in `casehubio/drafthouse` history.

---

### File 1: `debate.md` — LLM-to-LLM debate log

**Purpose:** canonical source of truth. Append-only. Structured and anchored.
Agents read this to understand the full debate; they append to it each round.
Optimised for machine communication and precise citation.

**Entry ID scheme:** `[R<round>-<AGENT>-<seq>]`
- Round: integer, starting at 1
- Agent: `REV` (reviewer) or `IMP` (implementer)
- Seq: zero-padded 3-digit integer

Examples: `[R1-REV-001]`, `[R2-IMP-003]`, `[R3-REV-002]`

These are markdown anchors (`<a name="R1-REV-001">`). Cross-references use
the bare ID inline: `→ [R1-REV-001]`.

**Entry types** (maps to Qhorus message types in #27):

| Type | Used when |
|---|---|
| `raise` | Agent raises a new point |
| `respond` | Agent responds to a prior point |
| `dispute` | Agent explicitly disputes a prior position |
| `agree` | Agent agrees with a prior position |
| `counter` | Agent accepts part, disputes part |
| `new-point` | Agent raises a new point mid-debate (any round) |
| `flag-human` | Agent signals human review is needed |

**Classification axes** (on `raise` and `new-point` entries only):

| Axis | Values |
|---|---|
| Priority | P1 (blocking) / P2 (important) / P3 (minor) |
| Scope | Systemic (pattern across spec) / Isolated (single instance) |

**Status per point** (updated inline on the originating `raise` entry):

| Status | Marker |
|---|---|
| Open | 🔴 |
| In progress | 🟡 |
| Agreed | ✅ |
| Resolved | ✅ |

**Reviewer/Implementer memo** (one per round, appended after entries):
Each agent writes a short reasoning memo — current model of the spec, working
hypotheses, patterns noticed, concerns not yet formally raised. Externalises
accumulated reasoning for session continuity.

**debate.md format:**

```markdown
# Debate Log
**Spec:** /absolute/path/to/spec.md
**Session:** drafthouse-20260601-a3f2

---

<!-- Round 1 — Reviewer -->

<a name="R1-REV-001"></a>
**[R1-REV-001]** `raise` · P1 · Isolated · §3.2
Both `start_review` and `begin_review` appear with no canonical form stated.
Status: 🔴 Open

<a name="R1-REV-002"></a>
**[R1-REV-002]** `raise` · P2 · Systemic · §4.1
No stated behaviour on network failure during `update_selection`.
Status: 🔴 Open

**REV memo R1:** §4 feels under-specified across the board — error handling absent
in multiple endpoints, not just 4.1. Suspect systemic gap.

---

<!-- Round 2 — Implementer -->

<a name="R2-IMP-001"></a>
**[R2-IMP-001]** `agree` · → [R1-REV-001]
Agreed. Standardising to `start_review` throughout (§3.2, §5.1).
→ [R1-REV-001] Status: ✅ Agreed

<a name="R2-IMP-002"></a>
**[R2-IMP-002]** `dispute` · → [R1-REV-002]
Retry is caller responsibility per MCP contract. Silence is intentional.
→ [R1-REV-002] Status: 🔴 Open

**IMP memo R2:** Reviewer's §4 pattern concern may hold but I want specific
instances before accepting systemic. Holding for R3.

---

<!-- Round 3 — Reviewer -->

<a name="R3-REV-001"></a>
**[R3-REV-001]** `counter` · → [R2-IMP-002]
Accepted on substance — retry is caller responsibility. But the contract
reference should be cited explicitly; silence reads as oversight not intent.
→ [R1-REV-002] Status: 🟡 In progress

<a name="R3-REV-002"></a>
**[R3-REV-002]** `new-point` · P2 · Systemic · §4.3
Timeout on `end_review` unspecified. Confirms pattern from [R1-REV-002].
Status: 🔴 Open

**[R3-FLAG-001]** `flag-human`
REV and IMP differ on what "MCP contract" covers. Human should clarify scope
before R4.
```

---

### File 2: `summary.md` — Human-readable projected view

**Purpose:** the human-facing view. Rewritten at the end of each round by a
lightweight agent call that reads `debate.md` and projects current state.
Optimised for human observation — scannable in one pass, no mental mapping.

**Organisation:** by point, not by round. Each point shows its full thread
(raise → respond → dispute → counter → agree) inline in sequence. The human
sees the complete arc of each point without navigating rounds.

**Resolved points** remain visible but visually de-emphasised (status marker
and thread still readable, header struck through). The record of how agreement
was reached is preserved.

**summary.md format:**

```markdown
# Review Summary
**Spec:** /absolute/path/to/spec.md · **Session:** drafthouse-20260601-a3f2
**Round:** 3 · **Updated:** 2026-06-01T11:42Z

---

## ✅ [R1-REV-001] ~~P1 · §3.2 — Tool naming inconsistent~~
> **REV R1 (raise):** Both `start_review` and `begin_review` appear — no canonical form.
> **IMP R2 (agree):** Standardising to `start_review` throughout (§3.2, §5.1).

---

## 🟡 [R1-REV-002] P2 · Systemic · §4.1 — Retry semantics absent
> **REV R1 (raise):** No stated behaviour on network failure during `update_selection`.
> **IMP R2 (dispute):** Retry is caller responsibility per MCP contract. Silence intentional.
> **REV R3 (counter):** Accepted on substance. But contract reference should be cited —
>   silence reads as oversight not intent.

---

## 🔴 [R3-REV-002] P2 · Systemic · §4.3 — Timeout absent  *(raised R3)*
> **REV R3 (new-point):** Timeout on `end_review` unspecified. Confirms §4.1 pattern.

---

⚑ **Human review needed** (R3): REV and IMP differ on what "MCP contract" covers.
Clarify scope before Round 4.
```

---

### State Machine

Each point follows:

```
Open → In progress → Agreed
     → Disputed   → In progress → Agreed
                               → flag-human
```

Status lives on the originating entry in `debate.md` and is mirrored in the
`summary.md` section header. Either agent may update status when they respond.

---

## Layer 2 — Agent Workflow (v1)

### Skills

**`spec-reviewer`** skill:
1. Reads spec (full, fresh from file)
2. Reads current `debate.md`
3. Raises new points and/or responds to open/disputed implementer positions
4. Appends entries to `debate.md` with correct IDs and anchors
5. Writes reviewer memo for this round
6. Sets `flag-human` if needed

**`spec-implementer`** skill:
1. Reads current `debate.md`
2. Responds to all open reviewer points (agree / dispute / counter)
3. May raise new points if reviewing reveals gaps
4. Appends entries to `debate.md`
5. Writes implementer memo for this round

**Summary regeneration:** a lightweight agent call after each round reads
`debate.md` and rewrites `summary.md`. This is a deterministic projection —
no creative judgment needed. Could be mechanised if the debate format is
sufficiently structured (future optimisation).

### Session Lifecycle

```
start-review(spec-path)
  → create session repo at ~/.drafthouse/reviews/<session-id>/
  → write debate.md header
  → commit (round 0)

[Human clicks Next]
  → dispatch spec-reviewer skill
  → skill appends to debate.md
  → summary regenerated
  → commit (round N)

[Human clicks Next]
  → dispatch spec-implementer skill
  → skill appends to debate.md
  → summary regenerated
  → commit (round N+1)

[Human clicks Run Until]
  → alternates reviewer/implementer automatically
  → stops when flag-human found in debate.md or human intervenes

[Human clicks Back]
  → git checkout <round-N-sha> in session repo
  → DraftHouse renders summary.md at that commit

[Human clicks Restart From Here]
  → git checkout -b restart/<label> <round-N-sha>
  → continue on new branch; prior branch preserved
```

### Same-Session Policy (v1)

Both agents run in the same Claude session. Context accumulates across rounds —
accumulated judgment is preserved. The manifest (debate.md) is the externalised
state; agents read it from disk, not from conversation history.

"Clear and restart" is an explicit user action (Restart From Here), not automatic.
See #26 for threshold-based auto-reset and sub-agent architecture.

---

## Layer 3 — DraftHouse Integration

- SSE watch on `summary.md` in the session repo — live update as each round commits
- Iteration list: `git log --oneline` on session repo — click any entry to view that round
- **Next:** dispatch appropriate skill (alternates reviewer/implementer)
- **Run Until:** auto-dispatch loop, stops on `flag-human` or human click
- **Back:** `git checkout <sha>` in session repo, re-render summary.md
- **Restart From Here:** `git checkout -b restart/<label> <sha>`

Full UI design deferred to a separate spec when DraftHouse UI layer is in scope.

---

## Qhorus Channel Mapping (v2 — tracked in #27)

The `debate.md` format is designed to mirror a Qhorus message schema:

| debate.md | Qhorus |
|---|---|
| Entry ID `[R1-REV-001]` | Message ID |
| Citation `→ [R1-REV-001]` | Correlation ID |
| Entry type (raise, dispute, agree…) | Message type |
| Round | Sequence group |

When #27 is implemented, `debate.md` becomes a rendered serialisation of the
channel message store. `summary.md` becomes a rendered projection of a channel
query. The Layer 1 format schema does not change — the storage backend does.

---

## References

- #23 — ReviewerChannelBackend (existing channel infrastructure)
- #24 — DraftHouseMcpTools (MCP surface for session lifecycle)
- #26 — Session continuity, context management, sub-agent architecture
- #27 — Map DebateChannel to Qhorus channel type

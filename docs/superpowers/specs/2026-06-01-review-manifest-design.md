# Review Manifest — Design Spec
**Date:** 2026-06-01  
**Status:** Draft (rev 2 — post review)
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
- The file format is designed as a domain-compatible extension of the Qhorus channel
  SPI so the channel integration (#27) adds a storage backend without changing the
  format schema

---

## Non-Goals (v1)

- Sub-agent spawning for focused analysis (→ #26)
- Per-round fresh context / threshold-based auto-reset (→ #26)
- Qhorus channel storage backend (→ #27)
- Provenance labelling (main agent vs sub-agent) (→ #26)
- In-band human response entries — human responses are out-of-band in v1 (→ Layer 3)
- Terminal states: Withdrawn, Overridden, Out of scope (→ v2)

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

Each review session is a standalone git repo, isolated from the project repo:

```
~/.drafthouse/reviews/<session-id>/
  debate.md       — append-only LLM-to-LLM debate log
  summary.md      — rewritten-per-round human-readable view
```

Session ID: `drafthouse-<YYYYMMDD>-<6-char-hex>` derived from spec path + timestamp.

Each round is one git commit (both files). Git history provides:
- Time-travel: `git checkout <round-N-sha>` (read-only navigation — see Back semantics)
- Diff between rounds: `git diff HEAD~1 HEAD`
- Restart from round N: `git checkout -b restart/<label> <round-N-sha>`

The session repo is isolated — no review commits appear in the project repo history.

---

### File 1: `debate.md` — LLM-to-LLM debate log

**Purpose:** canonical source of truth. Strictly append-only — no entry is ever
modified after it is written. Agents read this to understand the full debate history;
they append entries for each round. Optimised for machine communication and precise
citation.

**Entry ID scheme:** `[R<round>-<AGENT>-<seq>]`
- Round: integer, starting at 1
- Agent: `REV` (reviewer) or `IMP` (implementer)
- Seq: zero-padded 3-digit integer, unique within a round-agent pair

Examples: `[R1-REV-001]`, `[R2-IMP-003]`, `[R3-REV-002]`

Each entry ID is a markdown anchor (`<a name="R1-REV-001">`). Cross-references
use the bare ID inline: `→ [R1-REV-001]`.

**Entry types:**

| Type | Used when |
|---|---|
| `raise` | Agent raises a new point — any round, either agent |
| `agree` | Agent accepts a prior position |
| `dispute` | Agent explicitly rejects a prior position |
| `qualify` | Agent partially accepts and partially disputes a prior position |
| `flag-human` | Agent signals human review is needed before proceeding |

Notes:
- `raise` covers all new points regardless of round (replaces the redundant `new-point`
  distinction — the round is already encoded in the entry ID)
- `flag-human` uses the raising agent's ID: `[R3-REV-003]` type `flag-human`, not a
  special FLAG identifier
- **No neutral `respond` type.** Every response must commit to a position:
  `agree`, `dispute`, or `qualify`. Neutral exchanges ("which endpoint specifically?")
  are not permitted as responses — if an agent needs clarification, it writes a new
  `raise` entry with the question; the other agent answers it in the next round.
  This is deliberate: forcing commitment prevents debates from stalling in
  non-committal exchanges.

**Classification axes** (on `raise` entries only):

| Axis | Values |
|---|---|
| Priority | P1 (blocking) / P2 (important) / P3 (minor) |
| Scope | Systemic (pattern across spec) / Isolated (single instance) |
| Location | Optional — spec section (§4.1), heading, or free-form locator |

Location is metadata, not a classification axis. It is optional: specs without
section numbers use heading text or a brief description instead.

**Status per point:**

Status is never modified on the originating `raise` entry after it is written.
Status transitions are expressed as directives in response entries using
`→ [ID] Status: <marker>`. The summary parser follows the last status directive
for each point ID to determine current status.

| Status | Marker | Meaning |
|---|---|---|
| Open | 🔴 | Raised, no response yet |
| Active | 🟡 | Response received, not yet resolved |
| Pending Human | 🔵 | flag-human raised, awaiting human input |
| Agreed | ✅ | Both agents agree — terminal |

**Agent memo** (one per round, appended after all entries for that round):
Private working notes — the agent's current model of the spec, working hypotheses,
patterns noticed, concerns not yet formally raised. Memos have no IDs and are not
citable by other entries. They inform the next agent's reasoning but are not part
of the formal record. Memos do not appear in `summary.md`.

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
Standardising to `start_review` throughout (§3.2, §5.1).
→ [R1-REV-001] Status: ✅ Agreed

<a name="R2-IMP-002"></a>
**[R2-IMP-002]** `dispute` · → [R1-REV-002]
Retry is caller responsibility per MCP contract. Silence is intentional.
→ [R1-REV-002] Status: 🟡 Active

**IMP memo R2:** Reviewer's §4 pattern concern may hold but I want specific
instances before accepting systemic. Holding for R3.

---

<!-- Round 3 — Reviewer -->

<a name="R3-REV-001"></a>
**[R3-REV-001]** `qualify` · → [R2-IMP-002]
Accepted that retry is caller responsibility. But the contract reference must be
cited explicitly — silence reads as oversight, not intent.
→ [R1-REV-002] Status: 🟡 Active

<a name="R3-REV-002"></a>
**[R3-REV-002]** `raise` · P2 · Systemic · §4.3
Timeout on `end_review` unspecified. Confirms pattern from [R1-REV-002].
Status: 🔴 Open

<a name="R3-REV-003"></a>
**[R3-REV-003]** `flag-human`
REV and IMP differ on what "MCP contract" covers. Human should clarify scope
before R4.
→ [R1-REV-002] Status: 🔵 Pending Human
```

---

### File 2: `summary.md` — Human-readable projected view

**Purpose:** the human-facing view. Rewritten at the end of each round by a
deterministic parser that reads `debate.md` and projects current state. No LLM
call required — the structure of `debate.md` is sufficient. An LLM call is a
future option only if memo content is later included in the summary.

**Organisation:** by point, not by round. Each point shows its full thread
(raise → response entries in sequence) inline. The human sees the complete arc of
each point in one scan without navigating rounds.

**Resolved points** remain visible but visually de-emphasised (header struck through,
thread still readable). The record of how agreement was reached is preserved.

**summary.md format:**

```markdown
# Review Summary
**Spec:** /absolute/path/to/spec.md · **Session:** drafthouse-20260601-a3f2
**Round:** 3 · **Updated:** 2026-06-01T11:42Z

---

## ✅ ~~[R1-REV-001] P1 · §3.2 — Tool naming inconsistent~~
> **REV R1 (raise):** Both `start_review` and `begin_review` appear — no canonical form.
> **IMP R2 (agree):** Standardising to `start_review` throughout (§3.2, §5.1).

---

## 🔵 [R1-REV-002] P2 · Systemic · §4.1 — Retry semantics absent
> **REV R1 (raise):** No stated behaviour on network failure during `update_selection`.
> **IMP R2 (dispute):** Retry is caller responsibility per MCP contract. Silence intentional.
> **REV R3 (qualify):** Accepted that retry is caller responsibility. But the contract
>   reference must be cited explicitly — silence reads as oversight, not intent.

⚑ Pending human input: REV and IMP differ on what "MCP contract" covers. Clarify before R4.

---

## 🔴 [R3-REV-002] P2 · Systemic · §4.3 — Timeout absent
> **REV R3 (raise):** Timeout on `end_review` unspecified. Confirms §4.1 pattern.
```

---

### State Machine

```
                    qualify/dispute
                    (exchange continues)
                         ▲  │
Open ──► Active ──────────  └──► Agreed  ✅  (terminal)
          │
          └──► Pending Human ──► Active  (human resolves out-of-band)
```

- `Open` → `Active`: any `agree`, `dispute`, or `qualify` response
- `Active` → `Active`: `qualify` or `dispute` on an already-Active point; exchange
  continues until one agent agrees
- `Active` → `Agreed`: `agree` response
- `Active` → `Pending Human`: `flag-human` entry
- `Pending Human` → `Active`: human provides direction out-of-band; next agent proceeds

**Human responses are out-of-band in v1.** When a human resolves a `flag-human`
point, they communicate their decision in conversation. The next agent reads the
decision from context and continues — no human entry type is written to `debate.md`.
In-band human entries (`[R4-HUM-001]`) are Layer 3 scope.

**Known gaps (v2):** terminal states Withdrawn (raising agent drops the point),
Overridden (human decides directly), Out of scope (closed without resolving substance).

**Status authority:** the last `→ [ID] Status: <marker>` directive for a given
point ID in `debate.md` is authoritative. The summary parser follows this rule
to determine current status. No entry is modified after being written.

---

## Layer 2 — Agent Workflow (v1)

### Skills

**`spec-reviewer`** skill:
1. Reads spec (full, fresh from file)
2. Reads current `debate.md`
3. Raises new points (`raise`) and responds to open implementer positions
4. Appends entries with correct IDs and anchors; includes `→ [ID] Status:` directives
5. Writes reviewer memo for this round
6. Writes `flag-human` entry if human input is needed before next round

**`spec-implementer`** skill:
1. Reads current `debate.md`
2. Responds to all open reviewer points (`agree` / `dispute` / `qualify`)
3. May raise new points (`raise`) if review reveals gaps
4. Appends entries with correct IDs and anchors; includes `→ [ID] Status:` directives
5. Writes implementer memo for this round

**Summary regeneration:** a deterministic parser reads `debate.md` after each round
and rewrites `summary.md`. The parser: groups entries by originating point ID,
reconstructs the thread in entry order, applies the last status directive for each
point, renders agreed points with strikethrough headers.

### Session Lifecycle

```
start-review(spec-path)
  → create session repo at ~/.drafthouse/reviews/<session-id>/
  → write debate.md header, empty summary.md
  → git commit (round 0 — session open)

[Human clicks Next]
  → identify next agent (alternates REV/IMP; REV goes first)
  → dispatch skill
  → skill appends to debate.md
  → deterministic parser rewrites summary.md
  → git commit (round N)

[Human clicks Run Until]
  → auto-dispatch loop alternating REV/IMP
  → stops when: flag-human found in latest round, or human intervenes

[Human clicks Back]
  → git checkout <round-N-sha> in session repo (detached HEAD)
  → DraftHouse renders summary.md at that commit
  → session enters read-only navigation mode
  → Next and Run Until are disabled until Restart From Here is chosen

[Human clicks Restart From Here]
  → git checkout -b restart/<label> HEAD (from current navigation point)
  → session exits read-only mode; Next and Run Until re-enabled
  → prior branch preserved; new rounds commit to new branch

[Human navigates Back to HEAD]
  → git checkout main (or current session branch)
  → session exits read-only mode
```

### Same-Session Policy (v1)

Both agents run in the same Claude session. Accumulated context is preserved across
rounds. `debate.md` is the externalised state — agents read it from disk, not from
conversation history, ensuring the file is the single source of truth.

**v1 limitation:** No warning is given when context approaches window limits. Agent
output quality degrades gradually after approximately 6–8 rounds on a large spec —
symptoms include re-raising settled points or shallower reasoning. When degradation
is observed, use Restart From Here proactively. Threshold-based auto-reset and
sub-agent architecture are tracked in #26.

---

## Layer 3 — DraftHouse Integration

**Scope note:** Layer 3 is a substantial new DraftHouse feature, not an extension
of existing infrastructure. Current DraftHouse renders A/B diffs between two files;
the review session surface requires session discovery, a different render mode
(debate thread, not diff), control buttons, and round navigation. Full design is
deferred to a separate spec. The items below define the interface contract that
Layer 2 depends on.

Interface contract:
- SSE watch on `summary.md` in session repo — live update as each round commits
- Iteration list: `git log --oneline` on session repo — click any entry to navigate
- **Next:** dispatch appropriate skill (alternates REV/IMP)
- **Run Until:** auto-dispatch loop, stops on `flag-human` or human click
- **Back:** `git checkout <sha>` in session repo; enters read-only navigation mode
- **Restart From Here:** `git checkout -b restart/<label>` from current navigation point

---

## Qhorus Channel Mapping (v2 — tracked in #27)

The `debate.md` format is designed as a domain-compatible extension of the Qhorus
channel SPI. The `DebateChannel` type introduces debate-specific message types
(`raise`, `agree`, `dispute`, `qualify`, `flag-human`) registered as a domain
extension — these do not map to existing Qhorus types (QUERY/RESPONSE/STATUS/etc.)
because debate semantics differ from agent-task semantics.

The structural properties map directly:

| debate.md | Qhorus |
|---|---|
| Entry ID `[R1-REV-001]` | Message ID |
| Citation `→ [R1-REV-001]` | Correlation ID |
| Entry type (`raise`, `dispute`…) | Message type (domain extension) |
| Round comment boundary | Sequence group |

When #27 is implemented: `debate.md` becomes a rendered serialisation of the
`DebateChannel` message store; `summary.md` becomes a rendered projection of a
channel query.

The structural identity mapping is preserved: entry ID → message ID, citation →
correlation ID, entry type → domain message type. Status directive representation
(currently embedded in response entry content as `→ [ID] Status: 🟡 Active`) is
TBD in #27 — in Qhorus, status would naturally be a field on the message rather
than inline content, which may require a format schema adjustment at the status
line level.

---

## References

- #23 — ReviewerChannelBackend (existing channel infrastructure)
- #24 — DraftHouseMcpTools (MCP surface for session lifecycle)
- #26 — Session continuity, context management, sub-agent architecture
- #27 — Map DebateChannel to Qhorus channel type

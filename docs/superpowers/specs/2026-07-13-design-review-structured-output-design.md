# Design: Improve Design-Review Structured Output

**Issue:** casehubio/drafthouse#96
**Date:** 2026-07-13
**Scope:** soredium/design-review (Python) + drafthouse (Java)

## Problem

The design-review skill produces a structured conversation between two agents
(reviewer and implementor) about a design artifact. That structure is currently
embedded in markdown prose and extracted via regex — fragile, duplicated across
two languages (Python `parser.py` and Java `WorkspaceParser.java`), and lossy.

Three specific failures:
1. Important metadata (location, priority, dependencies) is buried in prose —
   not machine-extractable.
2. Two independent regex parsers must stay in sync. When the Python parser
   evolves, the Java parser diverges silently.
3. `verify_against_diff()` is fundamentally broken — it searches for a section
   number string in diff output rather than checking whether the section's
   content actually changed. A heuristic pretending to be verification.

## Solution

Three coordinated changes across both repos:

1. **Structured metadata lines** — add parseable line-prefix markers to the
   agent output format (LOCATION, PRIORITY, DEPENDS, EVIDENCE)
2. **JSONL sidecar files** — each response file gets a companion `.jsonl` with
   typed domain events, derived from parser output
3. **Mechanical evidence verification** — replace the broken diff heuristic
   with structured evidence claims that the orchestrator verifies against git

## 1. Structured Metadata Lines in Markdown

### Reviewer issues

Three new optional line-prefix markers immediately after each issue heading:

```markdown
### Missing failure mode for payment timeout
LOCATION: §4.1 Payment Flow
PRIORITY: HIGH
DEPENDS: R1-02

The spec doesn't handle the case where...
```

- `LOCATION:` — spec section reference (§N.N format)
- `PRIORITY:` — `HIGH`, `MEDIUM`, or `LOW` (aligns with `blocks.conversation.Priority`)
- `DEPENDS:` — comma-separated issue IDs this issue depends on

Defaults when absent: location=null, priority=LOW, depends=[].
Priority default matches `ConversationProjection.parsePriority()` behavior.

### Implementor FIXED responses

New repeatable `EVIDENCE:` marker immediately after the FIXED heading:

```markdown
### R1-01: FIXED
EVIDENCE: §4.1 | commit:abc123
EVIDENCE: §4.2 | commit:abc123

Updated §4.1 with terminal PAYMENT_FAILED state.
```

Format: `<location> | commit:<hash>` with optional `| lines:<start>-<end>` for
code-review/final-review modes. At least one EVIDENCE line required per FIXED
response.

These follow the existing line-prefix pattern (SIGNAL:, ASSUMPTION:, SETTLED:)
that agents already produce reliably.

### EVIDENCE enforcement

"Required" means soft enforcement across three layers:

1. **Agent prompt**: The format spec in `context.md` states EVIDENCE is required
   per FIXED response. The LLM is told to produce it.
2. **Parser**: Extracts EVIDENCE markers if present. Does not fail or reject
   if absent — the response is still valid.
3. **Verifier**: `verify_evidence()` with empty evidence returns
   `verified=False, "no evidence provided"`. The tracker records the issue as
   ADDRESSED but with a note flagging the gap.
4. **Reviewer visibility**: The next reviewer round sees the gap in the tracker
   and can raise it as a new concern.

Hard enforcement (rejecting the round and retrying the implementor) is not
implemented. The soft approach is preferred because LLM compliance is
probabilistic — the system degrades gracefully when evidence is missing rather
than blocking progress.

## 2. JSONL Event Schema

Each response file gets a companion JSONL sidecar (`reviewer-1.jsonl` alongside
`reviewer-1.md`). Each line is a self-describing domain event — not a parser
data dump, but a deliberate contract between the Python producer and Java
consumer.

### Event types

**From reviewer responses:**

```jsonl
{"event": "issue_raised", "round": 1, "id": "R1-01", "title": "Missing failure mode", "body": "...", "location": "§4.1", "priority": "HIGH", "depends": ["R1-02"]}
{"event": "assumption", "round": 1, "text": "Event store supports exactly-once delivery", "source": "reviewer-1.md"}
{"event": "round_signal", "round": 1, "role": "reviewer", "signal": "CONTINUE", "description": null}
```

**From implementor responses:**

```jsonl
{"event": "issue_fixed", "round": 1, "id": "R1-01", "sectionRef": "4.1", "evidence": [{"location": "§4.1", "commit": "abc123"}], "rationale": "Added terminal PAYMENT_FAILED state..."}
{"event": "issue_rejected", "round": 1, "id": "R1-02", "rationale": "Idempotency handled at gateway level..."}
{"event": "issue_escalated", "round": 1, "id": "R1-03", "rationale": "Genuine design decision — needs human input"}
{"event": "settled_decision", "round": 1, "text": "Strong consistency for financial writes", "fromIssue": "R1-04"}
{"event": "assumption", "round": 1, "text": "...", "source": "implementor-1.md"}
{"event": "round_signal", "round": 1, "role": "implementor", "signal": "CONTINUE", "description": null}
```

**Confirmations** (from reviewer round N+1, written into that reviewer's JSONL):

```jsonl
{"event": "confirmation", "round": 2, "id": "R1-01", "verdict": "resolved", "reason": ""}
```

Where `verdict` is `"resolved" | "accepted" | "contested"` — a discriminated
union matching the three confirmation outcomes. This replaces the previous
`resolved`/`accepted` boolean pair which allowed contradictory states.

### Schema rules

- Every line: `event` (type discriminator) + `round` (round number)
- Issue-scoped events: `id` present
- `body` and `rationale` include full prose — JSONL is self-sufficient
- `evidence`: array of `{location, commit, lines?}` objects
- Priority values: `HIGH`, `MEDIUM`, `LOW` — matching `blocks.conversation.Priority`
- `scope`: optional string on `issue_raised` events. Currently null for
  design-review (the skill doesn't produce scope metadata). The field exists
  to align with `PointClassification(priority, scope, location)` in P5 — the
  adapter passes null, which is the existing behavior. Adding scope categories
  (e.g., "correctness", "security", "performance") is a future enhancement.
- First line of each JSONL file is a version header:
  `{"event": "schema_version", "version": 1}`

### DraftHouse event mapping

| JSONL event | DraftHouse EntryType | Notes |
|---|---|---|
| `issue_raised` | `RAISE` | Point initiation |
| `issue_fixed` | `QUALIFY` | Implementor addresses with evidence; status → ACTIVE (needs reviewer verification) |
| `issue_rejected` | `COUNTER` | Implementor disagrees; status → ACTIVE |
| `issue_escalated` | `FLAG_HUMAN` | Needs human decision; status → ESCALATED |
| `confirmation` (verdict=resolved) | `VERIFIED` | Reviewer confirms fix correct; status → VERIFIED (terminal) |
| `confirmation` (verdict=accepted) | `AGREE` | Reviewer accepts rejection; status → AGREED (terminal) |
| `confirmation` (verdict=contested) | `DISPUTE` | Reviewer still disagrees; status → DISPUTED |
| `assumption` | `MEMO` | Informational — dispatched as memo content |
| `settled_decision` | `MEMO` | Informational — dispatched as memo content |
| `round_signal` | `ROUND_SNAPSHOT` | Intercepted by `DebateChannelProjection.apply()` — stored as channel message for timeline reconstruction but does not affect conversation state |

This mapping aligns with the shipped `WorkspaceReplayAdapter` and
`DebateChannelProjection.statusAfter()`. Note: `issue_fixed` maps to `QUALIFY`
(not `AGREE`) because a fix needs reviewer verification before the point is
resolved — `QUALIFY` → status `ACTIVE`, while `AGREE` → status `AGREED`
(terminal). This differs from the exploration spec (§6.2) which mapped
FIXED → AGREE; the adapter implementation corrected this during #95.

### PRIORITY and DEPENDS semantics

PRIORITY and DEPENDS are **display-only on the Python orchestration side**.
They are stored in the tracker and rendered in `tracker.md` for human
readability, and flow to DraftHouse via JSONL where PRIORITY maps to
`PointClassification.priority` (affecting rendering). They do not influence:
- Issue ordering in the implementor prompt
- Convergence decisions (all issues are equal for APPROVED gating)
- Issue processing order

Future enhancement: behavioral integration (address HIGH issues first, enforce
DEPENDS ordering) is a separate concern tracked outside this spec.

## 3. Python Parser Changes (`parser.py`)

### Enriched dataclasses

```python
@dataclass
class Issue:
    issue_id: str
    title: str
    body: str
    location: str | None = None
    priority: str = "LOW"
    depends: list[str] = field(default_factory=list)

@dataclass(frozen=True)
class Evidence:
    location: str
    commit: str
    lines: str | None = None

@dataclass(frozen=True)
class Confirmation:
    issue_id: str
    verdict: str  # "resolved" | "accepted" | "contested"
    reason: str = ""

@dataclass
class IssueResponse:
    issue_id: str
    status: str
    section_ref: str | None = None  # derived from prose (backward compat)
    rationale: str = ""
    body: str = ""
    evidence: list[Evidence] = field(default_factory=list)
    # When evidence is present, evidence[0].location supersedes section_ref
    # for verification. section_ref continues to be parsed from prose for
    # tracker.md rendering and old-format compatibility.
```

The `Confirmation` dataclass replaces the previous `is_resolved: bool` /
`is_accepted: bool` pair with a single `verdict` discriminator. This aligns
the Python type with the JSONL wire format — no conversion needed when
writing events. The orchestration code in `review.py` changes from
`if conf.is_resolved:` to `if conf.verdict == "resolved":` — mechanical
and clarifying.

### New regex patterns

```python
_LOCATION_RE = re.compile(r"^LOCATION:\s*(.+)$", re.MULTILINE)
_PRIORITY_RE = re.compile(r"^PRIORITY:\s*(HIGH|MEDIUM|LOW)\b", re.IGNORECASE | re.MULTILINE)
_DEPENDS_RE = re.compile(r"^DEPENDS:\s*(.+)$", re.MULTILINE)
_EVIDENCE_RE = re.compile(
    r"^EVIDENCE:\s*(.+?)\s*\|\s*commit:(\S+)(?:\s*\|\s*lines:(\S+))?\s*$",
    re.MULTILINE,
)
```

### Extraction behavior

`extract_new_issues()` scans the body between heading and next heading. LOCATION,
PRIORITY, DEPENDS lines at the top are extracted into fields and stripped from
`body` (prose doesn't contain metadata prefixes).

`extract_issue_responses()` scans for EVIDENCE lines at the top of FIXED response
bodies. Extracted into the evidence list and stripped from body.

All new fields have defaults — old workspaces without structured markers parse
unchanged.

## 4. Tracker Enrichment (`tracker.py`)

`TrackedIssue` gains fields to store the reviewer's metadata:

```python
@dataclass
class TrackedIssue:
    issue_id: str
    summary: str
    round_raised: int
    status: IssueStatus = IssueStatus.OPEN
    contested_rounds: int = 0
    commit_before: str = ""
    commit_after: str = ""
    section_ref: str = ""
    rationale: str = ""
    notes: str = ""
    location: str = ""       # from reviewer LOCATION:
    priority: str = "LOW"    # from reviewer PRIORITY:
    depends: list[str] = field(default_factory=list)  # from reviewer DEPENDS:
```

`Tracker.add_issue()` gains optional keyword arguments for these fields.
`Tracker.render()` displays them in the tracker.md issue entries:

```markdown
### R1-01: Missing failure mode for payment timeout
- **Raised:** Round 1
- **Status:** OPEN
- **Location:** §4.1 Payment Flow
- **Priority:** HIGH
- **Depends:** R1-02
```

## 5. JSONL Generation in `review.py`

After parsing each response file, `review.py` writes the companion JSONL.

```python
def _write_jsonl(ws: Path, role: str, round_num: int, events: list[dict]) -> None:
    jsonl_path = ws / "responses" / f"{role}-{round_num}.jsonl"
    tmp_path = jsonl_path.with_suffix(".jsonl.tmp")
    with open(tmp_path, "w") as f:
        f.write(json.dumps({"event": "schema_version", "version": 1}) + "\n")
        for event in events:
            f.write(json.dumps(event) + "\n")
    os.rename(tmp_path, jsonl_path)
```

**Integration points:**

- Reviewer round: after `extract_new_issues()`, `extract_signal()`,
  `extract_assumptions()` — build events from parse results, write JSONL,
  git-commit alongside markdown and tracker
- Implementor round: after `extract_issue_responses()`,
  `extract_settled_decisions()`, `extract_assumptions()` — same pattern
- Confirmations: written into the reviewer JSONL of the round that contains them

JSONL files are added to the same git commits as their markdown counterparts.

## 6. Evidence Verification (replaces `verify_against_diff()`)

`verify_against_diff()` is deleted. Replaced by a two-layer design that
preserves the existing pure/IO split between `tracker.py` and `review.py`:

**`tracker.py` — pure core** (data types + pure verification logic):

```python
@dataclass(frozen=True)
class EvidenceResult:
    verified: bool
    note: str = ""

def verify_evidence_against_diff(
    evidence: list[Evidence],
    diff: str,
    spec_content: str,
) -> EvidenceResult:
```

This is a pure function: diff string in, spec content in, result out. No
I/O, no subprocess, no file access. Same testability as the current
`verify_against_diff()` — string-in/result-out with no mocking needed.

**`review.py` — I/O wrapper** (git operations + file reads):

```python
def _verify_evidence(evidence: list[Evidence], spec_path: str) -> EvidenceResult:
    if not evidence:
        return EvidenceResult(verified=False, note="no evidence provided")
    e = evidence[0]
    diff = _git_diff_commit(e.commit, spec_path)
    if diff is None:
        return EvidenceResult(verified=False, note=f"commit {e.commit} not found")
    spec_content = Path(spec_path).read_text()
    return verify_evidence_against_diff(evidence, diff, spec_content)
```

`review.py` runs git, reads files, and passes the results to the pure
function. All git subprocess calls remain in `review.py` alongside the
existing `_get_git_diff()`, `_get_head_hash()`, and `_get_source_diff()`.

### Verification logic

**Spec modes** (spec-review, pre-review):
1. Extract section ref from `evidence.location` and commit hash from
   `evidence.commit`
2. `review.py` runs `git diff <commit>~1 <commit> -- <spec_file>` —
   verifying exactly the commit the implementor claims, not HEAD~1.
   If `<commit>~1` doesn't exist (initial commit of the spec file — git
   exits non-zero), fall back to `git show <commit> -- <spec_file>`. If
   the file exists in that commit, the entire file is new and all sections
   are trivially modified. Return `EvidenceResult(verified=True,
   note="initial commit — entire file is new")`.
3. Pass diff string and spec content to
   `verify_evidence_against_diff()` in tracker.py
4. Parse diff to find modified line ranges
5. Parse spec content to find line range of claimed section (see
   section-finding algorithm below)
6. Check overlap — verified if diff touched lines within that section

**Section-finding algorithm** (for step 4):

Given a section reference like `§4.1`:

1. Scan the spec file line-by-line for markdown headings (`^#{1,6}\s+`)
2. For each heading, extract any section number matching the pattern
   `(\d+(?:\.\d+)*)` at the start of the heading text (after `#` markers)
3. Match the target section ref against extracted numbers
4. Section starts at the matched heading line
5. Section ends at the next heading at the same or higher level (fewer
   or equal `#` characters), or end of file
6. If no heading matches, fall back to searching for the literal string
   `§<ref>` anywhere in the file — this catches references in prose
   when section numbers don't appear in headings
7. If no match at all, return `verified=False, "section §X not found in spec"`

**Code modes** (code-review, final-review):
1. Check `git show <evidence.commit>` exists
2. If `evidence.lines` provided, check diff touches those lines
3. If no lines, confirm commit exists and touches the claimed file

### Failure modes (surfaced, not swallowed)

- No evidence on FIXED response → `verified=False, "no evidence provided"`
- Commit hash doesn't exist → `verified=False, "commit abc123 not found"`
- Parent commit doesn't exist (initial commit) → fall back to `git show`,
  treat all sections as modified if file exists
- Section not touched in diff → `verified=False, "§4.1 not modified in commit abc123"`

Replaces the `verify_against_diff()` call site in the implementor processing loop.

### Breaking change: verify_against_diff() deleted

Issue #96 AC #5 says "verify_against_diff() updated to use structured evidence
when available." This spec instead deletes it entirely. This is a deliberate
design decision, not an oversight:

- `verify_against_diff()` is a heuristic that searches for a section number
  string in diff output — it doesn't verify anything meaningful
- Evidence-based verification is a full replacement, not an incremental upgrade
- Old workspaces without EVIDENCE markers are unaffected: verification only runs
  on new implementor responses during active rounds, and `_rebuild_tracker()`
  does not re-verify historical responses
- AC #5 should be updated to: "verify_against_diff() replaced by
  verify_evidence() using structured evidence markers"

## 7. Java `WorkspaceParser` Changes

### Enriched records

```java
public record Evidence(String location, String commit, String lines) {}

public record ParsedIssue(String issueId, String title, String body,
        String location, String priority, List<String> depends) {}

public record ParsedResponse(String issueId, String status, String sectionRef,
        String rationale, String body, List<Evidence> evidence) {}

public record ParsedConfirmation(
        String issueId,
        String verdict,  // "resolved", "accepted", "contested"
        String reason) {}
```

The `ParsedConfirmation` record replaces the previous `boolean resolved,
boolean accepted` pair with a single `verdict` discriminator. The adapter
changes from `if (conf.resolved())` / `if (conf.accepted())` to
`switch (conf.verdict())` — matching the pattern already used for response
status dispatch.

### JSONL reader

New `parseRoundFromJsonl()` method reads `.jsonl` files for a given round,
deserializes each line by `event` type discriminator, assembles a
`ParsedRound` object.

### Confirmation routing — unified in-source-round model

Both JSONL and markdown parsers use the same routing model: confirmations
are placed in the `ParsedRound` of the round they are **written in**, not
cross-round routed to the raising round.

**JSONL:** `parseRoundFromJsonl(n)` reads `reviewer-n.jsonl`. Confirmation
events carry `"round": n` and appear in round N's `ParsedRound`.

**Markdown:** `parseRoundFromMarkdown(n)` reads `reviewer-n.md` and extracts
confirmations from that file into round N's `ParsedRound`. This normalizes
the markdown parser to match the JSONL model — the current cross-round
routing (reading `reviewer-(n+1).md` when building round N) is removed.

The adapter dispatches confirmations with round `n` uniformly, regardless of
source format. This produces the same channel messages as before:
- Old routing: confirmation placed in round 1's ParsedRound, dispatched at `1 + 1 = 2`
- New routing: confirmation placed in round 2's ParsedRound, dispatched at `2`

Same `correlationId` (from `conf.issueId()`), same `inReplyTo` (from
`raiseMessageIds`), same round number on the channel message.

**Mixed-workspace safety:** With uniform routing, a markdown round 3 and a
JSONL round 4 cannot produce duplicate confirmations. Round 3's markdown
parser reads only `reviewer-3.md`. Round 4's JSONL parser reads only
`reviewer-4.jsonl`. No overlap.

### Selection logic — per-round fallback

```java
private static List<ParsedRound> parseRounds(Path workspaceDir) {
    Path responsesDir = workspaceDir.resolve("responses");
    if (!Files.isDirectory(responsesDir)) return List.of();

    int maxRound = discoverMaxRound(responsesDir);
    List<ParsedRound> rounds = new ArrayList<>();

    for (int n = 1; n <= maxRound; n++) {
        if (hasJsonlForRound(responsesDir, n)) {
            rounds.add(parseRoundFromJsonl(responsesDir, n));
        } else {
            rounds.add(parseRoundFromMarkdown(responsesDir, n));
        }
    }
    return rounds;
}
```

Per-round selection handles mixed workspaces: rounds 1-3 from before this
change parse from markdown, rounds 4+ parse from JSONL. Each round uses
whichever format is available, assembling a unified `List<ParsedRound>`. This
supports workspace resume across the format migration — no data loss.

Existing markdown parsing moves to `parseRoundFromMarkdown()` with
confirmation routing normalized to the in-source-round model (see
"Confirmation routing" above). Issues, responses, signals, assumptions,
and settled decisions parse unchanged.

### WorkspaceReplayAdapter simplification

With JSONL events mapping directly to `EntryType`, the adapter's conversion
becomes a straightforward event-type switch rather than multi-step
extraction-then-synthesis.

## 8. Agent Prompt Updates

The structured output format is defined in the `context.md` template
(`setup.py::_default_context_md()`). Two new subsections added to the
"Structured Output Format" section:

- "Issue metadata (reviewer: after each issue heading)" — documents LOCATION,
  PRIORITY, DEPENDS format with example
- "Evidence markers (implementor: FIXED responses only)" — documents EVIDENCE
  format with example

No changes to role-specific CLAUDE.md templates or `prompts.py` — format
lives in shared `context.md`.

## 9. Testing

### Python (soredium)

- `parser.py` unit tests: LOCATION/PRIORITY/DEPENDS extraction (present, absent,
  partial); EVIDENCE extraction (single, multiple, with/without lines, on
  non-FIXED responses); body text stripping of metadata lines
- `tracker.py` unit tests: `verify_evidence_against_diff()` — pure function
  tests (diff string + spec content in, result out). Valid evidence, missing
  evidence, section not in diff, initial commit (all sections modified).
  `verify_against_diff()` tests deleted.
- `review.py` integration: `_verify_evidence()` I/O wrapper with git fixture
  or subprocess mock. JSONL file creation, event serialization, old workspace
  compatibility

### Java (DraftHouse)

- `WorkspaceParserTest.java`: JSONL reading, enriched fields, old markdown
  fallback, mixed workspace (some rounds JSONL, some markdown)
- `WorkspaceReplayAdapterTest.java`: event-to-EntryType mapping, evidence
  metadata on replayed entries
- One real design-review run to validate LLM compliance with structured format

## File Changes

### soredium/design-review/ (edit in soredium, sync-local)

| File | Change |
|---|---|
| `parser.py` | Add Evidence/Confirmation dataclasses, enrich Issue/IssueResponse, replace Confirmation booleans with verdict discriminator, add LOCATION/PRIORITY/DEPENDS/EVIDENCE regexes and extraction |
| `tracker.py` | Enrich TrackedIssue with location/priority/depends, update add_issue()/render(), remove `verify_against_diff()`, add `verify_evidence_against_diff()` pure function with EvidenceResult |
| `review.py` | Add `_write_jsonl()`, call it after each parse step, add `_verify_evidence()` I/O wrapper, update implementor verification call site, update confirmation branches to use verdict |
| `setup.py` | Update `_default_context_md()` with new structured output format sections |

### drafthouse/server/runtime/

| File | Change |
|---|---|
| `WorkspaceParser.java` | Add Evidence record, enrich ParsedIssue/ParsedResponse, replace ParsedConfirmation booleans with verdict, add JSONL reader with per-round fallback, extract markdown parsing to separate method, normalize confirmation routing to in-source-round model |
| `WorkspaceReplayAdapter.java` | Simplify to event-type switch mapping, update confirmation dispatch to use verdict switch |
| `WorkspaceParserTest.java` | Add JSONL reading tests, verify old workspace fallback |
| `WorkspaceReplayAdapterTest.java` | Add event mapping tests |

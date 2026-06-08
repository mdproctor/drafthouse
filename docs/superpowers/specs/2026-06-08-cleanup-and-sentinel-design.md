# Design Spec — Cleanup and Sentinel Fixes

**Branch:** `issue-33-cleanup-and-sentinel`  
**Covers:** #33 (instance deregistration), #38 (retire [QUALIFY] prefix), #39 (META: sentinel collision)  
**Date:** 2026-06-08

---

## Overview

Three small correctness fixes in sequence. All are self-contained — each closes cleanly before the next begins. The order matters: #33 requires a qhorus build; #38 and #39 are pure drafthouse changes.

---

## Issue #33 — Instance Deregistration

### Problem

`InstanceService.register()` is called by `DraftHouseMcpTools.startReview()` and
`DebateMcpTools.startDebate()` to create per-session instance records in Qhorus.
These instances are never cleaned up:

- On **partial failure** (`startReview` / `startDebate` catch block): the channel and
  registry entry are cleaned up, but the registered instance is orphaned in Qhorus.
- On **normal session end** (`endReview` / `endDebate`): registry and optionally channel
  are cleaned up, but the instance remains until Qhorus `markStaleOlderThan()` runs.

`InstanceStore.delete(UUID id)` already exists in both `JpaInstanceStore` and
`InMemoryInstanceStore`. `InstanceService` simply does not expose it.

### qhorus Change

Add one method to `InstanceService`:

```java
public void deregister(String instanceId) {
    instanceStore.findByInstanceId(instanceId)
        .ifPresent(inst -> instanceStore.delete(inst.id));
}
```

No-op if the instance does not exist (idempotent). No Flyway migration needed.

Add matching `deregister(String instanceId) → Uni<Void>` to `ReactiveInstanceService`
for parity. Implementation: `findByInstanceId` reactive path → delete.

After adding, run `mvn install` on qhorus so the updated SNAPSHOT is available
to drafthouse.

### drafthouse Changes — Four Sites

**`DraftHouseMcpTools.startReview()` — partial failure cleanup:**

Track `boolean instanceRegistered = false` before the try block. Set to `true`
immediately after `instanceService.register(instanceId, ...)` succeeds. In the
catch block, add:

```java
if (instanceRegistered) {
    try { instanceService.deregister(instanceId); }
    catch (Exception ce) { LOG.warning("cleanup instance: " + ce.getMessage()); }
}
```

**`DraftHouseMcpTools.endReview()` — normal session end:**

After `registry.remove(channelId)`, add:

```java
try { instanceService.deregister(session.instanceId()); }
catch (Exception e) { LOG.warning("end_review: instance deregister failed: " + e.getMessage()); }
```

**`DebateMcpTools.startDebate()` — partial failure cleanup:**

Track `boolean revRegistered = false` and `boolean impRegistered = false`. Set each
flag immediately after the corresponding `instanceService.register()` call. In the
catch block, deregister each registered instance:

```java
if (revRegistered) {
    try { instanceService.deregister(revInstanceId); }
    catch (Exception ce) { LOG.warning("cleanup rev instance: " + ce.getMessage()); }
}
if (impRegistered) {
    try { instanceService.deregister(impInstanceId); }
    catch (Exception ce) { LOG.warning("cleanup imp instance: " + ce.getMessage()); }
}
```

**`DebateMcpTools.endDebate()` — normal session end:**

After `registry.remove(channelId)`, deregister both instances:

```java
try { instanceService.deregister(session.revInstanceId()); }
catch (Exception e) { LOG.warning("end_debate: rev instance deregister failed: " + e.getMessage()); }
try { instanceService.deregister(session.impInstanceId()); }
catch (Exception e) { LOG.warning("end_debate: imp instance deregister failed: " + e.getMessage()); }
```

### Tests

- `InstanceServiceTest`: verify `deregister` removes the instance; verify second call is no-op.
- `DraftHouseMcpToolsTest`: mock `InstanceService`; verify `deregister` is called in catch path
  and in `endReview`.
- `DebateMcpToolsTest`: same for both rev/imp instances in catch path and `endDebate`.

---

## Issue #38 — Retire [QUALIFY] Prefix

### Problem

`ReviewChannelProjection.handleResponse()` detects whether a reviewer response is
a qualification (discussion continues) or agreement (point resolved) by checking
`message.content().startsWith("[QUALIFY] ")`. The `DocumentReviewer` LLM is implicitly
expected to emit this prefix in its content. This is a content-prefix hack:
structured semantics encoded as free-form string content.

### Design — MessageType as Discriminator

The two response states map exactly onto existing Qhorus message types:

| Semantic | MessageType | Meaning |
|---|---|---|
| Qualify | `RESPONSE` | Dialogue continues — reviewer has more to say |
| Agree | `DONE` | Point resolved — reviewer is satisfied |

No content encoding needed. The type carries the semantic.

### `ReviewResult` (api module)

Add `qualify` boolean:

```java
public record ReviewResult(boolean declined, boolean qualify, String content) {
    public static ReviewResult agree(String content)   { return new ReviewResult(false, false, content); }
    public static ReviewResult qualify(String content) { return new ReviewResult(false, true, content); }
    public static ReviewResult decline(String reason)  { return new ReviewResult(true, false, reason); }
}
```

`declined=true` implies `qualify=false` (a declined response cannot qualify).

### `DocumentReviewer` @UserMessage

Replace the implicit prefix expectation with explicit field instruction:

```
If outside scope: declined=true, qualify=false, explain in content.
Otherwise: declined=false.
  qualify=true  → you are qualifying your position (discussion continues, more to say).
  qualify=false → you agree (this point is resolved, discussion concludes).
Response in content.
```

### `ReviewerChannelBackend.dispatch()`

Replace the single `MessageType type = result.declined() ? DECLINE : RESPONSE` with:

```java
MessageType type;
if (result.declined()) {
    type = MessageType.DECLINE;
} else if (result.qualify()) {
    type = MessageType.RESPONSE;
} else {
    type = MessageType.DONE;
}
```

### `ReviewChannelProjection`

Replace `handleResponse()` (which contained the prefix check) with two methods:

```java
case RESPONSE -> handleQualify(state, message);  // RESPONSE: qualify, discussion active
case DONE     -> handleAgree(state, message);    // DONE: agree, point resolved
```

`handleQualify()`: reads correlationId, appends `EntryType.QUALIFY` thread entry,
sets `ReviewStatus.ACTIVE`. Identical to old qualify branch minus prefix stripping.

`handleAgree()`: reads correlationId, appends `EntryType.AGREE` thread entry,
sets `ReviewStatus.AGREED`. Identical to old agree branch.

The `[QUALIFY] ` string is deleted from the codebase entirely.

### Tests

- `ReviewChannelProjectionTest`:
  - `apply_done_message_agrees_point`: DONE message with valid correlationId → AGREED status, AGREE entry
  - `apply_response_message_qualifies_point`: RESPONSE message → ACTIVE status, QUALIFY entry
  - Delete `apply_response_qualify_transitionsToActive_stripsPrefix` (the prefix test)
- `ReviewerChannelBackendTest`: verify `DONE` dispatched when `ReviewResult.agree(...)`, `RESPONSE` when `ReviewResult.qualify(...)`.
- `DocumentReviewerTest` (if present): no prefix in content.

---

## Issue #39 — META: Sentinel Collision

### Problem

`DebateChannelProjection.parseMeta()` uses `content.startsWith("META:")` to detect
structured debate messages. A legitimate debate body beginning with "META:" (natural
in technical discussion: "META: the spec says X") would be silently mis-routed
(entryType null → silent discard) or, if the body happened to include a valid
`entryType=` key, incorrectly parsed. The silent discard produces no log entry.

### Design

**Sentinel constant** (`io.casehub.drafthouse.debate.DebateProtocol` in the runtime module — both `DebateMcpTools` and `DebateChannelProjection` are in runtime, so no cross-module dependency needed):

```java
public final class DebateProtocol {
    public static final String META_SENTINEL = "DHMETA:";
    private DebateProtocol() {}
}
```

`` is ASCII SOH (Start Of Heading), a control character. No LLM produces SOH
in its text output. Valid in Java strings, UTF-8, PostgreSQL TEXT, and H2. The
`DHMETA` suffix makes the sentinel human-identifiable in hex dumps.

**`DebateMcpTools`:** Replace all `"META:..."` content constructions with
`DebateProtocol.META_SENTINEL + "..."`. No other logic changes.

**`DebateChannelProjection`:**

Replace `content.startsWith("META:")` with `content.startsWith(DebateProtocol.META_SENTINEL)`.

In `parseMeta()`: after extracting `headerLine`, if `meta.get("entryType")` is null,
log:

```java
LOG.log(System.Logger.Level.WARNING,
    "Structured debate message (sentinel present) has no entryType — discarded. Content prefix: {0}",
    headerLine.length() > 60 ? headerLine.substring(0, 60) + "..." : headerLine);
```

Messages that do NOT start with the sentinel are silently ignored as before
(plain content on a debate channel — not an error, just not a structured entry).

**Behaviour after fix:**

| Content starts with | Behaviour |
|---|---|
| `DHMETA:` + valid entryType | Parsed and folded correctly |
| `DHMETA:` + missing/unknown entryType | WARNING logged, state unchanged |
| `META:...` (old sentinel) | Treated as plain content, silently ignored |
| Anything else | Silently ignored |

### Tests

- `DebateChannelProjectionTest`:
  - `apply_raise_with_new_sentinel_parsed_correctly`
  - `apply_old_meta_sentinel_treated_as_plain_content`: message starting with `"META:"` → state unchanged, no parse
  - `apply_new_sentinel_with_missing_entrytype_logs_warning`
- `DebateMcpToolsTest`: verify encoded content uses new sentinel.

---

## Protocol Compliance

- **PP-20260604-6e8d5d** (mcp-tool-error-strings): all catch blocks return `"error: ..."` strings — unchanged.
- **PP-20260604-b88833** (prompt injection): `qualify` is a boolean flag resolved server-side; no LLM-controlled string flows into prompt routing — compliant.
- **PLATFORM.md — Agent mesh alignment**: `DONE` is a valid speech act for the review channel; semantics (resolved commitment) match Qhorus normative usage.

---

## Out of Scope

- `ReactiveInstanceService.deregister()` is added for SPI parity but not called by drafthouse (drafthouse uses blocking `InstanceService`). A separate issue should wire it if a reactive path is introduced.
- Instance cleanup for the `DraftHouseInstances.HUMAN_INSTANCE_ID` registered in `@PostConstruct` — this is a long-lived singleton, not session-scoped; out of scope.

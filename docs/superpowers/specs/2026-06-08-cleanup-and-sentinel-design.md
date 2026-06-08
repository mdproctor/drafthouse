# Design Spec — Cleanup and Sentinel Fixes

**Branch:** `issue-33-cleanup-and-sentinel`  
**Covers:** #33 (instance deregistration), #38 (retire [QUALIFY] prefix), #39 (META: sentinel collision)  
**Date:** 2026-06-08 (revised)

---

## Overview

Three correctness fixes in sequence on the same branch. Each closes before the next begins.
Order matters: #33 requires a qhorus build; #38 and #39 are pure drafthouse changes.

---

## Issue #33 — Instance Deregistration

### Problem

`InstanceService.register()` is called by `DraftHouseMcpTools.startReview()` and
`DebateMcpTools.startDebate()` to create per-session instance records in Qhorus.
These instances are never cleaned up:

- **Partial failure** (catch block in `startReview` / `startDebate`): channel and registry
  are cleaned up, but the registered instance is orphaned.
- **Normal session end** (`endReview` / `endDebate`): registry and optionally channel are
  cleaned up, but the instance persists until Qhorus `markStaleOlderThan()` sweeps it.

`InstanceStore.delete(UUID id)` already exists in both `JpaInstanceStore` and
`InMemoryInstanceStore`. `InstanceService` does not expose it.

### qhorus — InstanceService.deregister()

Add one method to `InstanceService`:

```java
@Transactional
public void deregister(String instanceId) {
    instanceStore.findByInstanceId(instanceId)
        .ifPresent(inst -> instanceStore.delete(inst.id));
}
```

`@Transactional` is required — every write method in `InstanceService` is transactional,
and the find-then-delete pair has a TOCTOU window without it. No-op if the instance does
not exist (idempotent). No Flyway migration needed.

### qhorus — ReactiveInstanceService.deregister()

Add reactive parity following the `Panache.withTransaction("qhorus", ...)` pattern used
by `register()` and `heartbeat()`:

```java
public Uni<Void> deregister(String instanceId) {
    return Panache.withTransaction("qhorus", () ->
        instanceStore.findByInstanceId(instanceId)
            .flatMap(opt -> opt.isPresent()
                ? instanceStore.delete(opt.get().id)
                : Uni.createFrom().voidItem()));
}
```

`Uni.createFrom().voidItem()` is correct for the no-op branch — both branches of the
ternary already return `Uni<Void>`, so `.replaceWithVoid()` does not apply here (it
transforms a non-Void `Uni`, not a `Uni<Void>`). `ReactiveInstanceStore.delete(UUID) →
Uni<Void>` already exists. Drafthouse does not currently call the reactive service, but
parity is maintained per the reactive-service build-gating protocol.

After adding, run `mvn install` on qhorus to make the updated SNAPSHOT available to
drafthouse.

### drafthouse — startReview() catch block

`instanceId` is derived from `channel.id`, which is only available inside the try block.
To make it accessible in the catch, hoist `String instanceId = null` **before** the try
block and assign it inside the try after `channelService.create()` succeeds:

```java
String instanceId = null;
Channel channel = null;
try {
    channel = channelService.create(...);
    String sessionId = channel.id.toString();
    instanceId = "drafthouse-reviewer-" + sessionId;
    instanceService.register(instanceId, ...);
    // ... rest of setup
} catch (Exception e) {
    if (channel != null) {
        if (instanceId != null) {
            try { instanceService.deregister(instanceId); }
            catch (Exception ce) { LOG.warning("cleanup instance: " + ce.getMessage()); }
        }
        try { registry.remove(channel.id); } catch (Exception ce) { ... }
        try { channelService.delete(channel.id, true); } catch (Exception ce) { ... }
    }
    return "error: " + e.getMessage();
}
```

No boolean flag is needed. If `instanceId != null` but registration had not yet succeeded
(exception thrown between ID assignment and `register()` call), `deregister()` is a
no-op because the instance does not exist — idempotency makes this safe.

### drafthouse — startDebate() catch block

Same pattern — two instances. Hoist both before the try:

```java
String revInstanceId = null;
String impInstanceId = null;
Channel channel = null;
try {
    channel = channelService.create(...);
    String debateSessionId = channel.id.toString();
    revInstanceId = "drafthouse-rev-" + debateSessionId;
    impInstanceId = "drafthouse-imp-" + debateSessionId;
    instanceService.register(revInstanceId, ...);
    instanceService.register(impInstanceId, ...);
    // ... rest of setup
} catch (Exception e) {
    if (channel != null) {
        if (revInstanceId != null) {
            try { instanceService.deregister(revInstanceId); }
            catch (Exception ce) { LOG.warning("cleanup rev instance: " + ce.getMessage()); }
        }
        if (impInstanceId != null) {
            try { instanceService.deregister(impInstanceId); }
            catch (Exception ce) { LOG.warning("cleanup imp instance: " + ce.getMessage()); }
        }
        try { registry.remove(channel.id); } catch (Exception ce) { ... }
        try { channelService.delete(channel.id, true); } catch (Exception ce) { ... }
    }
    return "error: " + e.getMessage();
}
```

### drafthouse — endReview() normal termination

After `registry.remove(channelId)`, deregister the session instance:

```java
try { instanceService.deregister(session.instanceId()); }
catch (Exception e) { LOG.warning("end_review: instance deregister failed: " + e.getMessage()); }
```

### drafthouse — endDebate() normal termination

After `registry.remove(channelId)`, deregister both instances:

```java
try { instanceService.deregister(session.revInstanceId()); }
catch (Exception e) { LOG.warning("end_debate: rev deregister failed: " + e.getMessage()); }
try { instanceService.deregister(session.impInstanceId()); }
catch (Exception e) { LOG.warning("end_debate: imp deregister failed: " + e.getMessage()); }
```

### Tests

**qhorus — `io.casehub.qhorus.instance.InstanceServiceTest` (existing class):**

Add two tests following the existing `@QuarkusTest @TestTransaction` pattern:

```java
@Test @TestTransaction
void deregister_removesInstance() {
    instanceService.register("agent-x", "Test agent", List.of("cap-a"));
    instanceService.deregister("agent-x");
    assertThat(instanceService.findByInstanceId("agent-x")).isEmpty();
}

@Test @TestTransaction
void deregister_isNoOpWhenInstanceNotFound() {
    assertDoesNotThrow(() -> instanceService.deregister("nonexistent"));
}
```

**drafthouse — `DraftHouseMcpToolsTest` and `DebateMcpToolsTest`:**

Verify `deregister` is called:
- In the catch path when a failure occurs after instance registration.
- In `endReview` / `endDebate` on normal session termination.

Mock `InstanceService`; verify `deregister(instanceId)` call with `Mockito.verify()`.

---

## Issue #38 — Retire [QUALIFY] Prefix

### Problem

`ReviewChannelProjection.handleResponse()` distinguishes qualify from agree by checking
`message.content().startsWith("[QUALIFY] ")`. `DocumentReviewer` (LangChain4j `@AiService`)
is implicitly expected to emit this prefix. This encodes a structural semantic as an
unvalidated content prefix.

### Design — ReviewResult.Outcome enum

Three response states, zero invalid combinations. Replace two booleans with a nested enum:

```java
public record ReviewResult(Outcome outcome, String content) {

    public enum Outcome { AGREE, QUALIFY, DECLINE }

    public static ReviewResult agree(String content)   { return new ReviewResult(Outcome.AGREE, content); }
    public static ReviewResult qualify(String content) { return new ReviewResult(Outcome.QUALIFY, content); }
    public static ReviewResult decline(String reason)  { return new ReviewResult(Outcome.DECLINE, reason); }
}
```

The two-boolean design (`declined`, `qualify`) allowed `declined=true, qualify=true` — a
semantically nonsensical fourth state. The enum eliminates it without adding complexity.
Factory methods preserve call-site readability.

LangChain4j serializes `Outcome` as `"outcome": "AGREE"` / `"QUALIFY"` / `"DECLINE"` in
the structured JSON response. Jackson deserializes the string to the enum constant.

### DocumentReviewer @UserMessage

Replace the implicit prefix expectation with an explicit `outcome` field instruction:

```
If the query is outside scope of document review: outcome=DECLINE, explain in content.
Otherwise:
  outcome=AGREE    → you agree — this point is resolved, discussion concludes.
  outcome=QUALIFY  → you qualify your position — discussion continues, you have more to say.
  outcome=DECLINE  → out of scope or unable to answer.
Response text in content.
```

### ReviewerChannelBackend.dispatch()

Replace the current binary type assignment with a switch on `result.outcome()`:

```java
MessageType type = switch (result.outcome()) {
    case AGREE   -> MessageType.DONE;
    case QUALIFY -> MessageType.RESPONSE;
    case DECLINE -> MessageType.DECLINE;
};
```

`DONE` requires `inReplyTo + correlationId` — both are already present in the dispatch
call (`inReplyTo` resolved from `messageService.findByCorrelationId()`, `correlationId`
forwarded from the incoming message).

### ReviewChannelProjection

Replace the monolithic `handleResponse()` (which contained the prefix check) with two
methods dispatched from `apply()`:

```java
case RESPONSE -> handleQualify(state, message);  // qualify: discussion continues
case DONE     -> handleAgree(state, message);    // agree: point resolved
```

`handleQualify()`: appends `EntryType.QUALIFY` thread entry, sets `ReviewStatus.ACTIVE`.  
`handleAgree()`: appends `EntryType.AGREE` thread entry, sets `ReviewStatus.AGREED`.

The `[QUALIFY]` string and prefix-check logic are deleted entirely.

### Tests

**`ReviewResultTest` (existing — update all four tests):**

All four existing tests use the 2-arg constructor `new ReviewResult(boolean, String)` or
the old `decline()` factory. After the change to `ReviewResult(Outcome, String)`:

- `responseCarriesContent()` → use `ReviewResult.agree("Looks good.")`, assert `outcome() == AGREE`
- `declineFactorySetsDeclinesTrue()` → assert `outcome() == DECLINE`
- `nullContentRejected()` → update constructor call to 2-arg `(Outcome, String)`
- `equalityByValue()` → unchanged if factories retain same signature (they do)

Add tests for the new enum:

```java
@Test void qualifyOutcome_isNotDecline_isNotAgree() {
    var r = ReviewResult.qualify("Still in dialogue.");
    assertThat(r.outcome()).isEqualTo(ReviewResult.Outcome.QUALIFY);
}
```

**`ReviewChannelProjectionTest` (existing — three changes):**

1. **Delete** `apply_response_qualify_transitionsToActive_stripsPrefix()` — tests the
   removed prefix logic; no longer valid.

2. **Update** `apply_response_agree_transitionsToAgreed_agentMapsToImp()` — currently
   uses `MessageType.RESPONSE` for the agree message. After the fix, RESPONSE means
   QUALIFY. Change the message type to `MessageType.DONE` and rename the test to
   `apply_done_message_agrees_point()`.

3. **Add** `apply_response_message_qualifies_point()` — RESPONSE message →
   `ReviewStatus.ACTIVE`, `EntryType.QUALIFY`.

**`ReviewerChannelBackendTest` (existing):**

Verify `DONE` dispatched for `ReviewResult.agree(...)`, `RESPONSE` for
`ReviewResult.qualify(...)`, `DECLINE` for `ReviewResult.decline(...)`.

---

## Issue #39 — META: Sentinel Collision

### Problem

`DebateChannelProjection.parseMeta()` and `bodyContent()` use `content.startsWith("META:")`
to detect structured debate messages. Debate content beginning with `"META:"` (natural in
technical discussion) is silently mis-routed. The `parseMeta()` substring offset `5`
(hardcoded as `"META:".length()`) would produce a corrupt `headerLine` after any sentinel
change unless updated. The sentinel itself is invisible in markdown renderings of the spec.

### Sentinel Constant

A new class `io.casehub.drafthouse.debate.DebateProtocol` in the runtime module
(accessible to both `DebateMcpTools` and `DebateChannelProjection`):

```java
public final class DebateProtocol {
    /** SOH (U+0001) prefix guarantees no LLM output ever begins with this sequence. */
    public static final String META_SENTINEL = "\u0001DHMETA:";
    private DebateProtocol() {}
}
```

The value is the six-character Java Unicode escape `\u0001` (SOH, U+0001) followed by
`DHMETA:` — eight characters total. The escape **must** be written as the six visible
ASCII characters `\u0001` in source, never as a literal SOH byte. Editors, copy-paste,
and version-control systems can silently drop or corrupt the invisible byte. The `DHMETA`
suffix is human-identifiable in hex dumps. `"\u0001DHMETA:".length()` is 8.

### DebateChannelProjection — parseMeta()

Two changes:

1. Replace `content.startsWith("META:")` with `content.startsWith(DebateProtocol.META_SENTINEL)`.

2. Replace the hardcoded offset `5` with `DebateProtocol.META_SENTINEL.length()`:

```java
private Map<String, String> parseMeta(String content) {
    Map<String, String> map = new HashMap<>();
    if (content == null || content.isBlank()) return map;
    if (!content.startsWith(DebateProtocol.META_SENTINEL)) return map;
    int headerEnd = content.indexOf("\n\n");
    String headerLine = headerEnd > 0
        ? content.substring(DebateProtocol.META_SENTINEL.length(), headerEnd)
        : content.substring(DebateProtocol.META_SENTINEL.length());
    for (String part : headerLine.split("\\|")) {
        int eq = part.indexOf('=');
        if (eq > 0) map.put(part.substring(0, eq).strip(), part.substring(eq + 1).strip());
    }
    if (map.get("entryType") == null) {
        LOG.log(System.Logger.Level.WARNING,
            "Structured debate message (sentinel present) has no entryType — discarded. Header: {0}",
            headerLine.length() > 80 ? headerLine.substring(0, 80) + "..." : headerLine);
    }
    return map;
}
```

The hardcoded `5` was `"META:".length()`. After the change, `"\u0001DHMETA:".length()`
is `8`. Using the constant prevents the offset from drifting again if the sentinel ever
changes.

### DebateChannelProjection — bodyContent()

`bodyContent()` also checks `content.startsWith("META:")`. Without this fix, the sentinel
header is included verbatim in every thread entry body returned by the summary renderer.

```java
private String bodyContent(String content) {
    if (content == null) return null;
    if (!content.startsWith(DebateProtocol.META_SENTINEL)) return content;
    int headerEnd = content.indexOf("\n\n");
    return headerEnd > 0 ? content.substring(headerEnd + 2) : "";
}
```

The `substring(headerEnd + 2)` correctly extracts the body regardless of sentinel length —
no offset change needed here. Only the `startsWith` check changes.

### DebateMcpTools

Replace all `"META:entryType=..."` content constructions with
`DebateProtocol.META_SENTINEL + "entryType=..."`. Affected sites: `raisePoint()`,
`respondTo()`, `flagHuman()`.

### Tests — DebateChannelProjectionTest (existing — update)

The `msg()` helper at line 21–24 hardcodes `"META:"`:

```java
String encodedContent = "META:" + metaHeader + "\n\n" + bodyContent;
```

Change to:

```java
String encodedContent = DebateProtocol.META_SENTINEL + metaHeader + "\n\n" + bodyContent;
```

This fixes all tests that use `msg()`.

Additionally, `nullActorType_doesNotThrow()` contains a hardcoded literal:

```java
"META:entryType=raise|agent=REV|round=1|priority=P1|scope=ISOLATED\n\nContent."
```

Replace with:

```java
DebateProtocol.META_SENTINEL + "entryType=raise|agent=REV|round=1|priority=P1|scope=ISOLATED\n\nContent."
```

Add new tests:

```java
@Test
void apply_oldSentinel_treatedAsPlainContent_notParsed() {
    // "META:" without SOH prefix → no longer recognized as structured
    ReviewState s = proj.apply(proj.identity(),
        new MessageView(null, null, "test", MessageType.QUERY,
            "META:entryType=raise|agent=REV|round=1|priority=P1|scope=ISOLATED\n\nBody.",
            "pt-x", null, null, null, ActorType.AGENT, null, null, 0));
    assertThat(s.points()).isEmpty();
}

@Test
void apply_newSentinelWithUnknownEntryType_stateUnchangedAndWarningLogged() {
    // Sentinel present but entryType unknown → WARNING, state unchanged
    ReviewState s = proj.apply(proj.identity(),
        msg(MessageType.QUERY, "pt-y", "entryType=unknown|agent=REV|round=1", "?"));
    assertThat(s.points()).isEmpty();
    // Warning is logged — verified by log capture or by asserting no exception
}
```

**DebateMcpToolsTest (existing — update):**

Any test assertions that check encoded content strings using `"META:"` must be updated to
use `DebateProtocol.META_SENTINEL`.

---

## Protocol Compliance

- **PP-20260604-6e8d5d** (mcp-tool-error-strings): all catch blocks return `"error: ..."` — unchanged.
- **PP-20260604-b88833** (prompt injection): `outcome` is an enum populated by LangChain4j structured
  output; no LLM-controlled string flows into prompt routing — compliant.
- **PLATFORM.md — Agent mesh alignment**: `DONE` speech act semantics (resolved commitment) align with
  Qhorus normative usage on the review channel.
- **InstanceService @Transactional rule**: all write methods carry `@Transactional` — `deregister`
  follows the established pattern.

---

## Out of Scope

- `HUMAN_INSTANCE_ID` registered in `DraftHouseMcpTools @PostConstruct` — long-lived singleton,
  not session-scoped; no cleanup needed.
- `ReactiveInstanceService.deregister()` is implemented for SPI parity but not called by
  drafthouse (blocking service path only). A separate issue covers reactive path adoption.

---
layout: post
title: "The Reviewer Wakes Up"
date: 2026-06-01
type: phase-update
entry_type: note
subtype: diary
projects: [drafthouse]
tags: [qhorus, langchain4j, channel-backend, quarkus]
---

Phase 2 is alive. A QUERY arrives on a `drafthouse/{sessionId}` channel, the
reviewer calls the LLM, and a RESPONSE comes back with the Commitment fulfilled.
The 501 stub is gone. The channel model is wired.

## What we built

The spec from last session held up well architecturally, but the pseudocode
was off in a few places. I verified the actual Qhorus API against source before
writing any code — which paid off. `ChannelBackend.post()` is
`post(ChannelRef channel, OutboundMessage message)`, not `post(Message msg)`.
The `OutboundMessage` carries a delivery-scoped UUID as `messageId`, not the
ledger Long ID we need for `inReplyTo`. Getting that Long requires a lookup:

```java
Long inReplyTo = messageService
    .findByCorrelationId(message.correlationId().toString())
    .map(m -> m.id)
    .orElse(null);
```

The `DocumentReviewer` interface came out clean. LangChain4j's runtime dynamic
`@SystemMessage` lets the personality be config-sourced now and swappable later
without touching the interface:

```java
@RegisterAiService
public interface DocumentReviewer {

    @SystemMessage("{{personality}}")
    @UserMessage("""
            Document A (original): {{documentA}}
            Document B (revised): {{documentB}}
            {{selectionContext}}
            User query: {{query}}
            """)
    ReviewResult review(String personality, String documentA,
                        String documentB, String selectionContext, String query);
}
```

`ReviewerChannelBackendFactory` is `@ApplicationScoped` and implements
`ReviewSessionRegistry`, which keeps `DraftHouseMcpTools` decoupled from the
backend implementation. Registration follows the deregister-then-register
pattern: `ChannelInitialisedEvent` fires on every `initChannel()` call, so the
backend must be idempotent.

## The store seam was broken

Integration testing exposed an upstream bug I hadn't hit before. In `@QuarkusTest`,
`casehub-qhorus-testing` supplies `InMemoryMessageStore` as `@Alternative @Priority(1)`,
displacing the JPA store for all writes. That part works. But `MessageService.findByCorrelationId()`
was implemented with a Panache active-record call:

```java
public Optional<Message> findByCorrelationId(final String correlationId) {
    return Message.find("correlationId", correlationId).firstResultOptional();
}
```

Panache goes straight to H2 — it never consults `InMemoryMessageStore`. The
QUERY is stored in the in-memory HashMap. The lookup hits H2, finds nothing.
`inReplyTo` is null. The backend returns early. The RESPONSE is never dispatched.

The symptom looked exactly like a transaction visibility problem — the QUERY
persisted within the same `dispatch()` transaction, and the lookup inside
`fanOut()` appeared to see nothing. Claude diagnosed it as a virtual-thread
boundary issue and built a `TransactionalRunner` with `REQUIRES_NEW` semantics
and a 20-attempt retry loop. I interrupted it. The actual flow is synchronous:
`fanOut()` is called within `dispatch()`'s `@Transactional` boundary, same
thread, no boundary to cross.

The real fix was upstream: add `correlationId` and `messageType` include-filters
to `MessageQuery`, update `MessageQueryJpql` with the JPQL predicates, and route
the four Panache-direct methods through `messageStore.scan()`. The
`invalidation_triggers` on an existing garden entry even predicted it: *"casehub-qhorus
MessageService refactored to route all data access through MessageStore interface."*
Filed as casehubio/qhorus#228, committed locally.

## What's passing

The final suite runs 44 tests, 0 failures. The new code has 8 unit tests and
5 integration tests:

- QUERY → RESPONSE path, with correct `inReplyTo` and `correlationId`
- DECLINE on explicit decline, on exception, on oversized documents
- Non-QUERY messages silently ignored
- No-session channel gets no backend (startup recovery gap)
- Session find/put/remove/updateSelection all exercised
- QUERY dispatches full RESPONSE → Commitment FULFILLED in the lifecycle test

The Commitment part matters. DraftHouse's stated goal is to prove the Qhorus
normative layer is usable end-to-end — not just that text comes back. The lifecycle
test queries `findResponseByCorrelationId()` after dispatch and verifies both the
message content and the sender identity. That's what Phase 2 is actually proving.

The Qhorus fix is installed locally. The push is pending a squash review on the
hook. Everything else is on `issue-23-reviewer-channel-backend`, ready to merge.

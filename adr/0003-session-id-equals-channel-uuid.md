# 0003 — SessionId equals channel.id UUID string (server-generated)

Date: 2026-06-04
Status: Accepted

## Context and Problem Statement

DraftHouseMcpTools returns a sessionId to callers; subsequent calls (`update_selection`,
`query_review`, `end_review`) supply this sessionId to identify the active session.
The internal registry is keyed by `UUID channelId`. The initial spec used a caller-provided
sessionId string independent of channelId, which required a secondary `findBySessionId()`
scan to map caller handle → registry key.

## Decision Drivers

* O(1) registry lookup is correct; O(n) scan for every `update_selection`/`end_review` call is unnecessary overhead
* Caller-controlled sessionId strings risk naming collisions across sessions
* Qhorus assigns a stable, globally-unique UUID to every channel on creation

## Considered Options

* **Client-provided sessionId** — caller supplies a string as the session handle; factory adds a secondary `Map<String, UUID>` for reverse lookup
* **Server-generated UUID, separate from channelId** — server generates a fresh UUID as sessionId; factory maintains two maps (UUID→session keyed by channelId, String→UUID keyed by sessionId)
* **sessionId = channel.id.toString()** — sessionId is the channelId UUID formatted as a string; single map, O(1) lookup by parsing the sessionId string back to UUID

## Decision Outcome

Chosen option: **sessionId = channel.id.toString()**, because it eliminates the secondary
index entirely. The channelId is already a stable, unique identifier; using it as the caller
handle avoids map duplication and removes the O(n) scan. The `ReviewSessionRegistry`
interface is unchanged (`find(UUID)` already exists).

### Positive Consequences

* No secondary `Map<String, UUID>` in `ReviewerChannelBackendFactory`
* No `findBySessionId()` method needed on `ReviewSessionRegistry`
* `update_selection` and `end_review` resolve the session in O(1) via `UUID.fromString(sessionId)`

### Negative Consequences / Tradeoffs

* `channelName` (the human-readable `"drafthouse/{slug}"`) must be stored on `ReviewSession` because it cannot be reconstructed from the channelId UUID — needed by `end_review` for channel deletion
* Callers receive a UUID string as their session handle rather than a human-readable name

## Links

* drafthouse#24
* docs/superpowers/specs/2026-06-04-drafthouse-mcp-tools-design.md
* ADR 0002 — document content on ReviewSession

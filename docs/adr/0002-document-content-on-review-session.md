# 0002 — Session-private document content stored on ReviewSession, not DataService

Date: 2026-06-04
Status: Accepted

## Context and Problem Statement

DraftHouseMcpTools must make document content available to ReviewerChannelBackend for each
review query. The initial spec used Qhorus DataService (store + claim + release lifecycle).
A spec review identified this as the wrong abstraction: DataService is designed for
cross-agent shared state, but review documents are session-private and ephemeral.

## Decision Drivers

* Documents are session-private — no cross-agent or cross-session sharing occurs
* DataService is designed for cross-agent shared state with explicit GC claims
* Using DataService adds 4 operations per session start (store × 2, claim × 2) and 4 per end (getByKey × 2, release × 2)
* The `DataService.claim(UUID artefactId, UUID instanceId)` API takes database UUIDs, not string keys — requires an extra instance lookup to get the UUID from the registered Instance object

## Considered Options

* **DataService** — store docAContent/docBContent as Qhorus SharedData with claim/release lifecycle
* **ReviewSession fields** — store content directly as `docAContent`/`docBContent` String fields on the record
* **Filesystem at query time** — read files fresh on each review query (no storage layer)

## Decision Outcome

Chosen option: **ReviewSession fields**, because documents are session-private and ephemeral.
Using DataService couples a session-scoped concern to a cross-agent shared bus, adds
unnecessary complexity (store, claim, release lifecycle), and requires type conversions
between string instanceId and the database instance UUID. Reading from the filesystem at
query time would require re-reading on every `post()` call and introduces I/O into the hot path.

### Positive Consequences

* No DataService injection in DraftHouseMcpTools or ReviewerChannelBackend
* Simpler `start_review` flow (no store/claim calls)
* Simpler `end_review` teardown (no release calls)
* Content is naturally bounded by `maxDocChars` — no GC eligibility concern

### Negative Consequences / Tradeoffs

* ReviewSession carries potentially large strings (up to `maxDocChars × 2`) in JVM heap
* Content is lost on server restart (not persisted) — acceptable for a local-only tool

## Links

* drafthouse#24
* docs/superpowers/specs/2026-06-04-drafthouse-mcp-tools-design.md

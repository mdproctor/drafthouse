# Handover — 2026-06-01

**Branch:** `main` (clean)

## Last Session

Implemented Phase 2 critique backend (#23): `DraftHouseConfig`, `DocumentReviewer
@RegisterAiService`, `ReviewerChannelBackend`, `ReviewerChannelBackendFactory`
(implements `ReviewSessionRegistry`), deleted `CritiqueResource` 501 stub.
44 tests passing. Fixed upstream store-seam bug in casehubio/qhorus#228
(`MessageService.findByCorrelationId()` now routes through `messageStore.scan()`
via new `MessageQuery.correlationId` + `messageType` filters). Work-end skill
updated for single-repo mode (scaffold cleanup after rebase). 2 protocols captured.

## Immediate Next Step

Start #24 — `DraftHouseMcpTools`: `start_review`, `update_selection`, `end_review`.
`ReviewSessionRegistry` is wired; factory and backend are live. MCP tools are the
remaining surface to expose the channel lifecycle externally.

```
/work
```

## What's Left

- #24 — DraftHouseMcpTools: start_review, update_selection, end_review · M · Low
- #25 — Lifecycle IT already written in #23 (ReviewSessionLifecycleIT). Assess
  whether to close or add a separate H2 JPA variant · XS · Low
- casehubio/parent#131 — add DraftHouse to PLATFORM.md, APPLICATIONS.md · S · Low

## What's Next

| # | Description | Scale | Complexity | Notes |
|---|-------------|-------|------------|-------|
| #24 | DraftHouseMcpTools: start_review, update_selection, end_review | M | Low | ReviewSessionRegistry live; factory wired |
| #25 | Reassess lifecycle test scope; likely close | XS | Low | ReviewSessionLifecycleIT already proves the lifecycle |
| parent#131 | DraftHouse in PLATFORM.md + APPLICATIONS.md | S | Low | |

## References

| Context | Where |
|---|---|
| Design spec | `docs/superpowers/specs/2026-05-30-critique-backend-spi-design.md` |
| Epic | casehubio/drafthouse#20 — Phase 2 critique backend |
| Latest blog | `blog/2026-06-01-mdp05-the-reviewer-wakes-up.md` |
| Key GEs | GE-20260530-4387cb (revised: upstream fixed + mock+fanOut pattern) |
| Protocols | PP-20260601-4fa0b2 (ChannelBackend idempotent reg), PP-20260601-403c5f (exception sanitization) |

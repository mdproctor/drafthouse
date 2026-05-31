# Handover — 2026-05-31

**Branch:** `main` (clean)

## Last Session

Designed, reviewed (3 external rounds), and partially implemented the Phase 2
critique backend. Design: Qhorus ChannelBackend participant + LangChain4j @AiService.
Issues #21 (Maven restructure: api/ + runtime/ split, casehub-qhorus +
quarkus-langchain4j-anthropic 1.9.1 wired) and #22 (domain model: ReviewSession,
ReviewResult, DocumentSide, ReviewSessionRegistry) closed and merged to main.
Branch `issue-11-word-level-diff` stamped for retention.

## Immediate Next Step

Start #23 — LangChain4j @AiService + Qhorus ChannelBackend. Begin with
`DocumentReviewer @AiService` and test the mock provider first — structured return
from Anthropic requires JSON schema (GE-20260528-e9564b, risk established in spec).

```
/work
```

## What's Left

- #23 — DocumentReviewer @AiService + ReviewerChannelBackend + ReviewerChannelBackendFactory · M · Med
- #24 — DraftHouseMcpTools: start_review, update_selection, end_review; delete CritiqueResource · M · Low
- #25 — Integration test: QUERY→Commitment→RESPONSE lifecycle with H2 Qhorus · M · Med
- casehubio/parent#131 — add DraftHouse to PLATFORM.md, APPLICATIONS.md, create deep-dive doc · S · Low

## What's Next

| # | Description | Scale | Complexity | Notes |
|---|-------------|-------|------------|-------|
| #23 | DocumentReviewer @AiService + Qhorus ChannelBackend | M | Med | Test mock provider first; ChannelService.create() needs initChannel() after (GE-20260526-5247f2) |
| #24 | DraftHouseMcpTools (start_review, update_selection, end_review) | M | Low | Depends on #23 |
| #25 | Integration test — QUERY→Commitment→RESPONSE lifecycle | M | Med | H2 + Qhorus; async event delivery may need Awaitility |

## References

| Context | Where |
|---|---|
| Design spec | `docs/superpowers/specs/2026-05-30-critique-backend-spi-design.md` |
| Epic | casehubio/drafthouse#20 — Phase 2 critique backend |
| LAYER-LOG.md | Layer 0.1 — Maven restructure entry |
| Latest blog | `blog/2026-05-31-mdp04-from-stub-to-channel.md` |
| Key GEs | GE-20260531-bd4b53 (langchain4j 0.26.1 compat), GE-20260526-5247f2 (initChannel), GE-20260528-e9564b (ResponseFormat.JSON schema) |

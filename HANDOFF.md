# Handover — 2026-06-03

**Branch:** `issue-030-arc42stories-bootstrap` (in progress)

## Last Session

Bootstrapped `ARC42STORIES.MD` (#30 closed): 2 Journeys, 5 Chapters (C1–C3 ✅,
C4–C5 🔲), 3 §9.4 Layer entries migrated from LAYER-LOG. Three-check quality gate
caught and fixed two path errors (SummaryProjector in api/ not runtime/,
ClaudeAgentSdkDebateAgentProvider package depth). CLAUDE.md updated; branch pushed
to both mdproctor and casehubio.

## Immediate Next Step

Raise PR for `issue-030-arc42stories-bootstrap` → casehubio/drafthouse, or continue
directly to #24 (DraftHouseMcpTools).

## Cross-Module

**We're blocking:**
- `casehubio/qhorus` — qhorus#232 (`project_channel` MCP tool + `ProjectionRenderer<S>` SPI)
  depends on qhorus#230 (shipped). `SummaryRenderer` stays local until #232 ships. · S · Low

**Blocked by:**
- `casehubio/platform` — platform#55 (`casehub-platform-agent` module) required before
  `ClaudeAgentSdkDebateAgentProvider` stub can be implemented · M · Med

## What's Left

- #24 — DraftHouseMcpTools: `start_review`, `update_selection`, `end_review` · M · Low
- #25 — ReviewSessionLifecycleIT: assess H2 variant · XS · Low
- casehubio/parent#145 — PLATFORM.md Cross-Repo Dependency Map + APPLICATIONS.md · S · Low

## What's Next

| # | Description | Scale | Complexity | Notes |
|---|-------------|-------|------------|-------|
| #24 | DraftHouseMcpTools: start_review, update_selection, end_review | M | Low | Layer 3 entry point; ReviewSessionRegistry live |
| #27 | Qhorus DebateChannel — DebateChannel type, AGREE/QUALIFY sub-classification | M | Med | Gates Layer 3 Qhorus integration |
| #28 | Session storage path configurability | S | Low | Hardcoded `~/.drafthouse/reviews/` |
| #32 | Debate minor quality improvements (formatter sorting, Clock in renderer, etc.) | S | Low | Batched from code review |
| platform#55 | casehub-platform-agent: Claude Agent SDK Quarkus wrapper | M | Med | Unblocks ClaudeAgentSdkDebateAgentProvider real impl |
| qhorus#232 | ProjectionRenderer SPI + project_channel MCP tool | S | Low | Then SummaryRenderer can implement it |

## References

| Context | Where |
|---|---|
| Architecture record | `ARC42STORIES.MD` (primary) · `LAYER-LOG.md` (source-of-truth draft) |
| Layer 1 spec | `docs/superpowers/specs/2026-06-01-review-manifest-design.md` |
| Layer 2 spec | `docs/superpowers/specs/2026-06-02-review-manifest-layer2-impl-design.md` |
| Epic | casehubio/drafthouse#20 — Phase 2 critique backend (parent) |
| Latest blog | `blog/2026-06-03-mdp04-arc42stories-bootstrapped.md` |
| Key GEs | GE-20260603-7ea359 (ChannelProjection is pure Java SPI, no CDI needed) |
| Platform issues | casehubio/platform#55 (casehub-platform-agent), casehubio/parent#145 (doc sync) |

# Handover — 2026-06-05

**Branch:** `main` (clean)

## Last Session

Closed #25 (ReviewSessionLifecycleIT — the integration test that was never running due to `*IT.java` naming). Key findings: `ChannelGateway.fanOut()` delivers to backends on virtual threads — delivery is async, not synchronous as the spec assumed. This required Awaitility. Two spec review rounds caught `CommitmentState.CANCELLED` (doesn't exist — use DECLINED), `findResponseByCorrelationId` filtering RESPONSE-only (useless for DECLINE tests), and `@TestTransaction` breaking virtual-thread visibility. `ReviewSessionRegistryImpl` extracted from `ReviewerChannelBackendFactory` as a production refactor. Epic #20 closed. Chapter 4 complete.

Garden: GE-20260605-494ed0 (virtual thread delivery), GE-20260605-73c9d6 (CommitmentState.DECLINED).

## Immediate Next Step

```
/work
```

Pick up #31 — `ChannelProjection` SPI migration (unblocked, qhorus#230 ✅ shipped). Or #27 (DebateChannel) for the next major chapter.

## What's Left

- #31 — Migrate DebateChannel local file-parser to `ChannelProjection` SPI (unblocked) · M · Med
- #34 — Remove unnecessary `@QuarkusTest` from `DebateRoundTripTest` · XS · Low

## What's Next

| # | Description | Scale | Complexity | Notes |
|---|-------------|-------|------------|-------|
| #31 | Migrate to ChannelProjection SPI — replaces local file-parser | M | Med | Unblocked: qhorus#230 ✅ |
| #27 | Qhorus DebateChannel — DebateChannel type, AGREE/QUALIFY sub-classification | M | Med | C5 chapter |
| #28 | Session storage path configurability | S | Low | Hardcoded `~/.drafthouse/reviews/` |
| #34 | Remove `@QuarkusTest` from `DebateRoundTripTest` | XS | Low | Pure domain logic, no CDI/DB needed |

## References

| Context | Where |
|---|---|
| Architecture record | `ARC42STORIES.MD` (§9.4 for layer entries) |
| Layer 1–4 specs | `docs/superpowers/specs/` |
| Epic #20 | casehubio/drafthouse#20 — Phase 2 critique backend ✅ CLOSED |
| Latest blog | `wksp/blog/2026-06-05-mdp06-the-test-that-never-ran.md` |
| Key GEs | GE-20260605-494ed0 (virtual thread delivery), GE-20260605-73c9d6 (CommitmentState.DECLINED) |

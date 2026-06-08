# Handover — 2026-06-08

**Branch:** `main` (clean)

## Last Session

Shipped #27 (debate channel write path): `DebateMcpTools` (6 tools), `DebateChannelProjection` (META: content header dispatch), `ReviewChannelProjection` (renamed from old `DebateChannelProjection`), `ReviewStatus.DISPUTED` (non-terminal), `EntryType.COUNTER`. Key runtime discovery: Qhorus `artefactRefs` validates UUID-only at dispatch time despite String API — debate metadata moved to `META:key=value|...\n\n<body>` content prefix (GE-20260608-757be3, #39 tracks cleanup). #27 closed.

## Immediate Next Step

```
/work
```

Pick up #39 (S/Low — safer META: prefix sentinel) or #26 (L/High — sub-agent architecture design).

## What's Left

- **#33** — Orphaned reviewer instance on `start_review` partial failure · S · Med — blocked: needs `InstanceService.deregister()` from platform
- **#38** — Retire `[QUALIFY]` prefix from `ReviewChannelProjection` — requires `DocumentReviewer @AiService` change · S · Med
- **#39** — Replace `META:` sentinel with something agents won't generate (current prefix can collide with text starting "META:") · S · Low

## What's Next

| # | Description | Scale | Complexity | Notes |
|---|-------------|-------|------------|-------|
| #39 | Safer debate content-header sentinel | S | Low | Quick fix; unblocks confidence in debate correctness |
| #38 | Retire [QUALIFY] from ReviewChannelProjection | S | Med | Requires changing DocumentReviewer response format |
| #26 | Review loop: session continuity, sub-agent architecture | L | High | Design issue; needs brainstorm before any code |
| #33 | Orphaned reviewer instance cleanup | S | Med | Hard-blocked: needs InstanceService.deregister() from platform |

## References

| Context | Where |
|---|---|
| Architecture record | `ARC42STORIES.MD` (C5 complete, §9.4 Debate Channel layer entry) |
| Design spec | `docs/superpowers/specs/2026-06-07-debate-channel-design.md` |
| Garden entry | GE-20260608-757be3 (artefactRefs UUID-only runtime constraint) |
| Latest blog | `wksp/blog/2026-06-08-mdp09-naming-debt-silent-rollback.md` |
| GitHub | `casehubio/drafthouse` |
| PLATFORM.md update pending | casehubio/parent#196 |

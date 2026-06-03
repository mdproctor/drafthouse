# Design Journal — issue-030-arc42stories-bootstrap

## 2026-06-03 — Bootstrap session

### Decisions

**Generate as one document, not section-by-section** — cross-section references (Chapter entries → Layer entries, §12 risks → issues) require the full document in context to stay consistent. Piecemeal generation produces reference drift.

**SummaryProjector placed in `server/api/`** — `ChannelProjection<S>` is a pure Java SPI; Qhorus discovers implementations at startup without CDI. Enables placement in the zero-dependency api/ module, keeping the domain portable for Claudony embedding. No CDI annotation required or appropriate.

**Three Chapters marked complete, two pending** — C1 (Scaffold), C2 (Platform Wiring), C3 (Review Domain) map to closed issues #15, #21, #29. C4 (MCP Surface, #24) and C5 (Debate Channel, #27) pending.

**LAYER-LOG.md retained** — not retired at bootstrap per arc42stories-primary-record-declaration protocol; kept as source-of-truth draft until ARC42STORIES.MD is reviewed and verified complete.

### Quality gate findings

Three-check sweep before closing:
- §12 issue refs (#24, #28): both open ✅
- Key files: all 11 classes found; SummaryProjector path corrected (api/ not runtime/), ClaudeAgentSdkDebateAgentProvider path corrected (/debate/claude/ subdirectory) ✅
- CDI annotations: @DefaultBean on LangChain4j provider, @Alternative @Priority(1) on Claude Agent SDK stub ✅

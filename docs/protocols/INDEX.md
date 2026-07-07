# DraftHouse Protocols

| File | Rule | Applies to |
|---|---|---|
| [playwright-jvm-warmup.md](playwright-jvm-warmup.md) | global-setup.js starts a shared Quarkus JVM; specs reuse it via QUARKUS_PORT | global-setup.js, global-teardown.js, main.js, helpers.js |
| [playwright-one-describe-per-spec.md](playwright-one-describe-per-spec.md) | One describe block per spec file — multiple blocks each spawn a JVM and cold-start | electron-tests/e2e/*.spec.js |
| [playwright-kill-stale-processes.md](playwright-kill-stale-processes.md) | Kill stale Electron/Quarkus processes before any test run | all Playwright E2E test runs |
| [playwright-jserrors-in-afterall.md](playwright-jserrors-in-afterall.md) | All describe blocks must destructure jsErrors and guard-assert in afterAll | electron-tests/e2e/*.spec.js |
| [playwright-page-lifecycle.md](playwright-page-lifecycle.md) | @QuarkusTest Playwright classes must use @WithPlaywright and @BeforeEach/@AfterEach page.close() | server/src/test/java/io/casehub/drafthouse/e2e/ |
| [playwright-render-complete-signal.md](playwright-render-complete-signal.md) | Wait for [data-diff-chunk] as render-complete signal — not content elements | server/src/test/java/io/casehub/drafthouse/e2e/ |
| [mcp-tool-error-strings.md](mcp-tool-error-strings.md) | @Tool methods must return "error: ..." strings — never propagate exceptions | DraftHouseMcpTools and any future @Tool class |
| [mcp-tool-llm-prompt-injection.md](mcp-tool-llm-prompt-injection.md) | @Tool parameters must not flow raw into LLM prompts — use config or server-side allowlist | DraftHouseMcpTools @Tool methods that interact with @AiService |
| [drafthouse-config-mock-two-level.md](drafthouse-config-mock-two-level.md) | Tests mocking DraftHouseConfig must use two-level mocking — separate mock per sub-interface, stub intermediate first | Any JUnit test mocking DraftHouseConfig |
| [channel-projection-actor-type.md](channel-projection-actor-type.md) | ChannelProjection.apply() must use MessageView.actorType() for actor classification — never sender strings | Any ChannelProjection or RenderableProjection in casehub-drafthouse |
| [debate-message-sentinel-encoding.md](debate-message-sentinel-encoding.md) | Debate channel messages must use DebateProtocol.META_SENTINEL — hardcoded prefix causes silent discard | DebateMcpTools (encoding), DebateChannelProjection (decoding) |
| [mcp-session-instance-cleanup.md](mcp-session-instance-cleanup.md) | MCP session lifecycle methods must deregister all Qhorus instances on normal end and partial failure | DraftHouseMcpTools, DebateMcpTools start_*/end_* methods |
| [debate-restart-context-not-entry-type.md](debate-restart-context-not-entry-type.md) | Infrastructure provenance message types (RESTART_CONTEXT) must be intercepted as string literals before EntryType.valueOf() — not added to the enum | DebateChannelProjection.apply(); any new provenance message type added to debate channels |
| [filtering-projection-content-check.md](filtering-projection-content-check.md) | Consumers of filtering ChannelProjection decorators must check domain state content directly — not ProjectionResult.isEmpty() | DebateMcpTools; any future caller of RoundBoundedProjection or similar filtering projections |
| [channel-projection-apply-must-not-throw.md](channel-projection-apply-must-not-throw.md) | ChannelProjection.apply() must never throw — discard malformed messages with a log; ProjectionService.fold() has no exception handling | Any ChannelProjection or RenderableProjection implemented in casehub applications |
| [panel-configure-idempotency.md](panel-configure-idempotency.md) | Panel configure() must be idempotent — guard document-level listeners with #initialized flag | All Web Component panels registering document.addEventListener in configure() |

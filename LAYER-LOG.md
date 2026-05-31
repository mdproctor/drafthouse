# LAYER-LOG — DraftHouse

Architecture record of what was built at each integration layer. Each entry is complete
when the layer closes.

**Migration note:** This file will migrate to `ARC42STORIES.MD §9.4` Layer Entries when
that document is bootstrapped. See `../parent/docs/arc42stories-spec.md` and
`../parent/docs/arc42stories-casehub-profile.md`.

---

## Layer 0 — Scaffold and Infrastructure

**Started:** 2026-05-26
**Completed:** 🔲

### Summary
Migrated from `mdproctor/md-compare` to `casehubio/drafthouse`. Removed Electron
shell (browser-only UI). Renamed all artifacts to `io.casehub.drafthouse`. Integrated
with CaseHub parent BOM, CI, dashboards, and website.

### Accountability gaps closed
| Gap | What breaks without it | Closed by |
|-----|----------------------|-----------|
| No CaseHub identity | Can't use foundation modules (Qhorus, LangChain4j) | Parent POM + BOM registration |
| Electron dependency | Requires npm/Sparge for dev and test | Quarkus-only architecture |

### Key wiring
- `ui.dir` JVM property tells UiResource where to find `index.html` and `styles.css`
- URL query params `?a=<path>&b=<path>` replace Electron IPC for initial file loading
- Relative API URLs replace `http://127.0.0.1:${port}` — same-origin serving

### Architectural decisions
Dropped Electron in favour of browser-based UI served by Quarkus. This eliminates
npm, Sparge dependency, and the process manager. Trade-off: no native file dialog —
replaced with `prompt()` for now, but the MCP tool surface will be the primary way
to load documents.

### Pattern introduced
Browser-served Quarkus UI with URL query param initialization.

### Pattern anchor
`UiResource.java` — `serveFile()` method serves static assets from `ui.dir`.

### Gotchas
🔲

### Pattern to replicate
1. Serve HTML/CSS from Quarkus via a catch-all resource with configurable root dir
2. Use relative API URLs in the frontend (no port configuration needed)
3. Pass initial state via URL query params instead of IPC

### Navigation
`git log --grep="#15" --oneline`

---

## Layer 0.1 — Multi-Module Maven Restructure + Qhorus/LangChain4j Dependency Wiring

**Started:** 2026-05-31
**Completed:** 2026-05-31
**Issue:** #21 (epic #20)

### Summary
Split the flat `server/` Quarkus app into a multi-module Maven project (`api/` + `runtime/`)
per the platform module-tier-structure protocol. Added `casehub-qhorus 0.2-SNAPSHOT` and
`quarkus-langchain4j-anthropic 1.9.1` as dependencies. Wired the Qhorus `qhorus` named
datasource with H2 in-memory for dev/test.

### Accountability gaps closed
| Gap | What breaks without it | Closed by |
|-----|----------------------|-----------|
| Single-module structure | Can't separate pure-Java domain API from Quarkus runtime | `api/` + `runtime/` split |
| No Qhorus dependency | Can't use channel messaging, commitments, SharedData | `casehub-qhorus 0.2-SNAPSHOT` |
| No LangChain4j | Can't define `@AiService` for reviewer | `quarkus-langchain4j-anthropic 1.9.1` |
| No qhorus datasource | Qhorus extension fails startup without named datasource | H2 `qhorus` datasource + `MODE=PostgreSQL` |

### Key wiring
- `server/api/` — pure Java, no framework deps; will hold `ReviewSession`, `ReviewResult`, `ReviewSessionRegistry`
- `server/runtime/` — Quarkus app, depends on `api/`; holds all resources and new reviewer classes
- `<maven.compiler.parameters>true</maven.compiler.parameters>` in runtime — required by `AiServicesProcessor` (GE-20260525-a8bd9a)
- `quarkus-langchain4j 0.26.1` rejected — incompatible with Quarkus 3.33+; 1.9.1 (built against 3.33.1) verified on 3.34.3
- CORS scoped to `%dev` and `%test` profiles — was wildcard in default profile

### Pattern introduced
Two-module (`api/` + `runtime/`) hexagonal split for CaseHub application-tier projects.
`api/` is a pure-Java jar with no heavy framework deps; `runtime/` is the Quarkus application.

### Pattern anchor
`server/api/pom.xml` and `server/runtime/pom.xml`

### Gotchas
- Build order: `mvn install -DskipTests` on full reactor before running selective `mvn test -pl runtime` — Quarkus generate-code runs before inter-module compile if reactor order not respected
- `ChannelService.create()` does NOT register in `ChannelGateway` — must call `initChannel()` explicitly after channel creation (GE-20260526-5247f2)
- `ResponseFormat.JSON` without schema throws on Anthropic — `@AiService` returning a record type must use a full `JsonSchema` (GE-20260528-e9564b)

### Navigation
`git log --grep="#21" --oneline`

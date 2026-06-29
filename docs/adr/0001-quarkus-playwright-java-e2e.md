# 0001 — E2E test framework for DraftHouse browser UI

Date: 2026-05-29
Status: Accepted

## Context and Problem Statement

The Electron-based Playwright suite from `md-compare` was deleted during the
DraftHouse migration (#15). A replacement E2E suite is needed to cover the browser
UI — diff rendering, scroll sync, word-level diff, navigation, and the diff legend.

## Decision Drivers

* DraftHouse is a Java/Quarkus project; keeping all tests in one `mvn test` run is
  strongly preferred over maintaining two toolchains
* The Quarkus server already runs in-process for `@QuarkusTest` — no external process
  management needed
* Electron dependency is undesirable; tests should run against the Quarkus HTTP server
  in a real browser

## Considered Options

* **Option A** — Java Playwright via `quarkus-playwright` quarkiverse extension
* **Option B** — Node.js Playwright pointing at `localhost:9002` (Quarkus test port)

## Decision Outcome

Chosen option: **Option A**, because it integrates cleanly with the existing `@QuarkusTest`
infrastructure, requires no separate toolchain, and keeps all tests in one `mvn test` run.

### Positive Consequences

* Single `mvn test` command runs all 37 tests (6 REST-Assured + 31 Playwright)
* Quarkus starts and stops automatically — no external process management
* Test classes are consistent Java: same annotations, same Maven lifecycle, same CI config
* `@TestHTTPResource` resolves the test server URL without hardcoded ports

### Negative Consequences / Tradeoffs

* Java Playwright API is more verbose than the JS equivalent (no arrow functions,
  explicit `evaluate()` calls for browser-side JS)
* `quarkus-playwright` is a quarkiverse extension outside the Quarkus platform BOM —
  version must be pinned manually and verified against Quarkus compatibility
* First run downloads Chromium (~200MB); CI needs a browser install step and cache
  (tracked in #19)

## Pros and Cons of the Options

### Option A — Java Playwright + quarkus-playwright

* ✅ Single toolchain — `mvn test` covers everything
* ✅ Quarkus lifecycle managed automatically via `@QuarkusTest`
* ✅ `@TestHTTPResource` resolves correct test port without hardcoding
* ✅ No Electron dependency
* ❌ More verbose Java Playwright API vs JS
* ❌ Extension outside Quarkus BOM — manual version pin required

### Option B — Node.js Playwright pointing at Quarkus server

* ✅ Familiar API (JS/TS, arrow functions, first-class async)
* ✅ Richer Playwright ecosystem (more examples, better docs)
* ❌ Separate toolchain — npm + Node.js alongside Maven
* ❌ External server process management (start jar, poll /api/ping, teardown)
* ❌ CI needs two separate test steps
* ❌ `JavaServer` process manager would need to be adapted from Electron context

## Links

* Spec: `docs/superpowers/specs/2026-05-29-quarkus-playwright-e2e-design.md`
* Closes: #18

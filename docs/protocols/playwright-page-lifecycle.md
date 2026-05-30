---
id: PP-20260529-200474
title: "Playwright @QuarkusTest classes must use @WithPlaywright and close pages after each test"
type: rule
scope: repo
applies_to: "All @QuarkusTest classes in server/src/test/java/io/casehub/drafthouse/e2e/"
severity: critical
refs:
  - docs/superpowers/specs/2026-05-29-quarkus-playwright-e2e-design.md
violation_hint: "Missing @WithPlaywright causes @InjectPlaywright to inject null (NPE). Missing @AfterEach page.close() exhausts the Quarkus server HTTP connection pool via open SSE EventSource connections; test 3+ times out at waitForSelector instead of failing fast."
created: 2026-05-29
---

Every `@QuarkusTest` Playwright class must carry `@WithPlaywright` at class level and manage page lifecycle via `@BeforeEach void openPage()` / `@AfterEach void closePage()`. The `@InjectPlaywright BrowserContext` is shared across test methods; each method creates one `Page` in `@BeforeEach` and closes it in `@AfterEach`. Omitting `@WithPlaywright` causes a null `BrowserContext`; omitting `page.close()` leaves SSE `/api/watch` connections open, exhausting the Quarkus server's HTTP connection pool and causing the third test (and beyond) to hang rather than fail fast.

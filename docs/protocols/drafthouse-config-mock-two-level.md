---
id: PP-20260606-f15545
title: "Tests mocking DraftHouseConfig must use two-level mocking for nested sub-interfaces"
type: rule
scope: repo
applies_to: "Any JUnit test class that constructs mock(DraftHouseConfig.class)"
severity: important
refs:
  - server/runtime/src/test/java/io/casehub/drafthouse/DraftHouseMcpToolsTest.java
violation_hint: "when(config.reviewer().maxDocChars()) throws NullPointerException in setUp() — not in a @Test method"
garden_ref: GE-20260606-668cee
created: 2026-06-06
---

`DraftHouseConfig` uses nested sub-interfaces (`Reviewer`, `Storage`). A plain `mock(DraftHouseConfig.class)` returns `null` for all reference-type methods, so chaining `config.reviewer().maxDocChars()` in a stub throws `NullPointerException` during `when()` setup — not at test execution. Always create a separate mock for each sub-interface and stub the intermediate method first: `DraftHouseConfig.Reviewer reviewer = mock(DraftHouseConfig.Reviewer.class); when(config.reviewer()).thenReturn(reviewer);` before stubbing any leaf methods. Do not use `RETURNS_DEEP_STUBS` — it hides unstubbed leaves that silently return null/0.

# Round 1 — Reviewer

## Overview

Brief overview of findings.

---

### R1-01: Missing error handling in parser

The parser does not handle malformed input. Section §3.2 describes the expected format but the implementation silently drops invalid lines.

ASSUMPTION: All input files are UTF-8 encoded.

---

### R1-02: API endpoint returns wrong status code

The /api/parse endpoint returns 200 on validation failure. Section 4.1 specifies that validation errors should return 422.

---

### R1-03: Race condition in concurrent access

Multiple threads can modify the shared state without synchronization.

SIGNAL: CONTINUE
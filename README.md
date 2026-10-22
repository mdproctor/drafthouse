# DraftHouse

[![Build](https://github.com/casehubio/drafthouse/actions/workflows/publish.yml/badge.svg?branch=main)](https://github.com/casehubio/drafthouse/actions/workflows/publish.yml) [![Open PRs](https://img.shields.io/github/issues-pr/casehubio/drafthouse)](https://github.com/casehubio/drafthouse/pulls)

MCP-driven document review tool. Any LLM client — Claude Code, Claudony, or
anything that speaks MCP — can open documents, run adversarial debates between
reviewer agents, and let humans participate concurrently through the browser UI.

## What It Does

### Document Comparison

Side-by-side markdown diff with LCS line matching, word-level change highlights,
colour-coded minimap, scroll sync, and inline change annotations. Supports
multi-document working sets with switchable comparison pairs and document
version timelines that snapshot content at each debate round.

### Adversarial AI Debates

Two or more LLM agents debate document quality through a Qhorus channel. A
reviewer raises points (with priority, scope, and location); an implementer
responds (agree, counter, dispute, qualify, decline). Points track through a
structured status flow — OPEN, ACTIVE, AGREED, DISPUTED, DECLINED, VERIFIED,
DEFERRED — visible in real time through the review tracker panel.

Debates support sub-agent dispatch for focused analysis, session branching and
restart-from-round for context management, round-bounded projections for
summary at any point, and context tracking to monitor LLM token usage.

### Human-in-the-Loop

The human participates as a concurrent channel member, not a stop-the-world
interrupt. Five actions available through the review tracker panel:

| Action | What it does |
|--------|-------------|
| **Comment** | Add context to any review point — agents see it, status unchanged |
| **Override** | Set HUMAN_OVERRIDE status — terminal, authoritative, no further debate |
| **Raise** | Create a new review point from text selection in the diff |
| **Reprioritise** | Change a point's priority when the reviewer's assessment is wrong |
| **Batch accept/defer** | Approve or defer all low-priority points at once |

Human actions flow into the Qhorus channel (displayed in real time alongside
agent entries) and are written to `decisions/human-round-{n}.md` files in the
workspace directory for agents to read at the next round.

### Brainstorming

Interactive option cards with status tracking (exploring, recommended,
eliminated, selected), convergence detection, and an integrated terminal for
live Claude Code sessions.

### Live Workspace Monitoring

Connect to an in-progress design-review workspace and watch the debate unfold
in real time — agent status, cost tracking, new entries pushed as they're
written. Also replays completed review workspaces from their response files.

## Use Cases

| Domain | How DraftHouse applies |
|--------|----------------------|
| **Design spec review** | Adversarial debate catches design flaws before implementation — primary use case |
| **Code review** | Load before/after files, debate correctness, security, performance |
| **Contract / legal review** | Agents argue opposing interpretations, human overrides on judgment calls |
| **Technical writing** | Reviewer debates clarity, accuracy, completeness against a style guide |
| **Architecture decisions** | Structured debate with evidence, human weighs in on trade-offs |
| **Compliance review** | Agent checks against regulation, human flags domain-specific exceptions |
| **Requirements validation** | Debate completeness, consistency, feasibility — human prioritises what matters |
| **Incident post-mortem** | Compare before/after state, debate root cause, human directs investigation |
| **Research paper review** | Adversarial scrutiny of methodology, claims, evidence |
| **API design review** | Debate naming, consistency, backward compatibility |

The pattern across all of these: **structured adversarial scrutiny with human
authority over the outcome.** The agents do the exhaustive analysis; the human
makes the judgment calls. Neither stops the other.

## Integration

DraftHouse is designed to be embedded in any MCP client. The server exposes MCP
tools for programmatic control and a browser UI for visual interaction — they
compose independently.

```
┌─────────────────────┐     MCP      ┌──────────────────┐
│  Claude Code        │─────────────▶│                  │
│  Claudony           │              │   DraftHouse     │
│  Any MCP client     │              │   Server         │
└─────────────────────┘              │                  │
                                     │  (Quarkus +      │
┌─────────────────────┐   WebSocket  │   Qhorus +       │
│  Browser UI         │◀────────────▶│   Quinoa)        │
│  (review tracker,   │     HTTP     │                  │
│   diff panel, feed) │─────────────▶│                  │
└─────────────────────┘              └──────────────────┘
```

The MCP client starts debates, raises points, loads workspaces. The browser
shows the live state and lets the human act. Neither requires the other —
a headless MCP session works without a browser, and the browser works without
knowing which client started the debate.

## Quick Start

```bash
# Build
mvn -f server/pom.xml package -DskipTests

# Run
java -jar server/runtime/target/drafthouse-server-runner.jar

# Open browser
open "http://localhost:9001/?a=/path/to/file-a.md&b=/path/to/file-b.md"
```

Query parameters:
- `?a=` and `?b=` — file paths for initial diff comparison
- `?debate=` — debate session ID to auto-connect
- `?mode=brainstorm` — brainstorming layout (terminal + brainstorm panels)

## MCP Tools

### Review

| Tool | Description |
|------|-------------|
| `start_review` | Start a document review session |
| `update_selection` | Set the current text selection scope |
| `query_review` | Query the current review state |
| `end_review` | End a review session |
| `list_reviewers` | List available reviewer agents |
| `get_reviewer_instructions` | Get a reviewer's system prompt |

### Debate

| Tool | Description |
|------|-------------|
| `start_debate` | Start an adversarial debate on a spec |
| `raise_point` | Raise a review point (priority, scope, location) |
| `respond_to` | Respond to a point (agree, dispute, qualify, counter, declined) |
| `flag_human` | Escalate a point for human attention |
| `get_debate_summary` | Get the projected debate state |
| `end_debate` | End the debate session |
| `report_context` | Report context window usage |
| `post_memo` | Post a per-round reasoning memo |
| `request_subagent` | Dispatch a sub-agent for focused analysis |
| `restart_from_round` | Branch the session from a specific round |

### Documents

| Tool | Description |
|------|-------------|
| `add_document` | Add a document to the working set |
| `remove_document` | Remove a document from the working set |
| `list_documents` | List all documents in the working set |
| `set_comparison` | Set the A/B comparison pair |
| `export_debate_summary` | Export the debate summary to a file |

### Workspace

| Tool | Description |
|------|-------------|
| `load_workspace` | Replay a completed review or watch one in progress |

### Brainstorm

| Tool | Description |
|------|-------------|
| `start_brainstorm` | Start a brainstorming session |
| `present_options` | Present option cards |
| `update_option` | Update an option's status |
| `set_recommendation` | Mark an option as recommended |
| `mark_eliminated` | Eliminate an option |
| `mark_selected` | Select the final option |
| `end_brainstorm` | End the brainstorming session |

## REST API

### Human Actions

All endpoints at `/api/debate/{debateSessionId}/human/`:

| Method | Path | Description |
|--------|------|-------------|
| POST | `/comment` | Comment on a review point |
| POST | `/raise` | Raise a new review point |
| POST | `/override` | Override a point's status (terminal) |
| POST | `/prioritise` | Change a point's priority |
| POST | `/batch` | Batch accept or defer multiple points |

### Browser-Facing

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/ping` | Health check |
| GET | `/api/file?path=` | Read a local file |
| GET | `/api/debate/sessions` | List active debate sessions |
| POST | `/api/debate/{id}/selection` | Store text selection scope |
| DELETE | `/api/debate/{id}/selection` | Clear text selection |
| GET | `/api/debate/{id}/documents` | List working set documents |
| POST | `/api/debate/{id}/comparison` | Set comparison pair |
| GET | `/api/debate/{id}/snapshot/{index}` | Get document snapshot content |
| GET | `/api/brainstorm/sessions` | List active brainstorm sessions |
| PATCH | `/api/brainstorm/{id}/options/{optionId}` | Update option status |
| WS | `/api/ws` | WebSocket push (debate events, file changes) |
| WS | `/api/terminal` | PTY-over-WebSocket terminal |

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `quarkus.http.port` | 9001 | Server port |
| `casehub.drafthouse.persistence.enabled` | false | Enable JPA session persistence |

## Tech Stack

Quarkus 3.34, casehub-qhorus (channels + projections), casehub-agency
(conversation fold, context tracking), casehub-pages (workbench layout),
LitElement (Shadow DOM panels), Quinoa (frontend bundling), pty4j (terminal),
Playwright (E2E tests).

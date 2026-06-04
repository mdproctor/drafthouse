---
layout: post
title: "The MCP surface ships, and the bug we almost missed"
date: 2026-06-04
type: phase-update
entry_type: note
subtype: diary
projects: [drafthouse]
tags: [mcp, qhorus, cdi, review-backend]
---

Four issues closed in one branch: three small quality fixes and the main event — DraftHouseMcpTools.

The quality fixes (#32, #5, #19) were overdue. Debate package had three stream passes where one Comparator would do, `syncPanelDOM` was re-parsing markdown on every call including selection-only updates, and the CI was downloading Playwright browsers from scratch on every run. Small, satisfying to clear.

DraftHouseMcpTools was the interesting one. The spec went through two rounds before I was happy with it.

**The stale snapshot.** The first spec had `ReviewerChannelBackend` holding `final ReviewSession session` — a snapshot captured at construction. `update_selection` calls would replace the registry entry with a new record, and the backend would never know. Selection context would always be empty. Completely silent. We caught it in spec review, before a line of implementation was written. The fix is straightforward: inject the registry, call `find(channelId)` on every `post()` invocation. But finding it required actually asking "wait — if updateSelection replaces the map entry, who reads the new value?" The original design didn't ask that.

**DataService was wrong.** The first spec stored document content in Qhorus DataService — store, claim, release. DataService is for cross-agent shared state with explicit GC lifecycle. Documents in a review session are session-private and ephemeral. Using DataService for them meant four extra operations at session start, four at teardown, and a type mismatch (DataService.claim takes database UUIDs, not string keys). Moving content directly onto `ReviewSession` deleted all of that. The spec got shorter; the implementation got shorter.

**The missing tool.** The original three tools — start_review, update_selection, end_review — gave an LLM client a way to open and close a session but no way to actually ask for a review. The backend had `ReviewerChannelBackend.post()` handling QUERY messages, but nothing dispatched a QUERY to the channel. Adding `query_review` completed the surface. It's the obvious gap in retrospect.

**SessionId as channelId.** The initial design had the caller supplying a sessionId string, which required a secondary index to map it to the channel UUID. The cleaner design: `start_review` generates the channel, returns `channel.id.toString()` as the sessionId. Subsequent calls parse it back as a UUID for a direct registry lookup. One less map, no scan.

The final surface — `start_review`, `update_selection`, `query_review`, `end_review` — replaces the deprecated `ReviewSessionResource` REST scaffold. Eighteen unit tests, all green. The `ReviewerChannelBackend` now reads the live session on every invocation, which means selection updates actually reach the reviewer.

One thing open: `InstanceService` has no `deregister` method, so reviewer instances registered at session start become stale rather than being explicitly cleaned up at end. The staleness GC handles it eventually. It's filed (#33) and acceptable for a local tool.

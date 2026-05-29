## Introduction

DraftHouse is a side-by-side markdown comparison tool that highlights differences between documents.
It uses an LCS diff algorithm to identify changed, added, and removed blocks.
This paragraph is identical in both documents.

Markdown content is rendered using marked.js with syntax highlighting via highlight.js.
The tool supports live file watching via a Server-Sent Events stream from the Quarkus backend.

## Features

The diff viewer shows changes between two documents using colour-coded blocks.
Modified blocks are marked with a border; deleted blocks appear only on the A-side;
inserted blocks appear only on the B-side.

Word-level differences within modified blocks are highlighted at the token level.
The LCS word diff algorithm preserves inline formatting such as bold and code spans.

### Word Changes

This sentence has some **modified** words that differ from the other document.
The unchanged tokens around the changed words should not be highlighted.
Only the specific words that changed will receive a highlight mark element.

## Scroll Sync

Scroll synchronisation uses heading anchors extracted from both panels.
Matched headings define piecewise linear interpolation segments.
Between matched headings, scroll position is interpolated proportionally.

The anchor list is rebuilt whenever files are reloaded or panels are swapped.
Unmatched headings on one side are skipped; only paired headings create anchors.

## Navigation

The next and previous buttons navigate between non-equal diff chunks.
Keyboard shortcuts n and p are equivalent to the topbar buttons.
The chunk counter shows the current position as N / total.

Clicking a bar on the minimap canvas scrolls both panels to that position.
The minimap draws red bars for A-side changes and green bars for B-side changes.

## Summary

This section appears only in document A and has no counterpart in document B.
It will be marked as a deleted block with the diff-del CSS class applied.
The minimap will draw a red bar at this section's vertical position.

This paragraph adds more content to ensure the panel is tall enough to scroll.
Additional lines of text make the scrollHeight exceed the clientHeight of the panel body.
Without scrollable content the scroll sync tests cannot verify that panels actually move.

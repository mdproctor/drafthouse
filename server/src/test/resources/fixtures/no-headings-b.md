This is a plain document with no headings of any level.
It contains only paragraphs so that the scroll anchor builder finds nothing to match.

When both panels contain content like this, the scroll anchor list will have only two entries:
the start anchor at position zero and the end anchor at maximum scroll position.
The interpolation between these two points is equivalent to percentage-based sync.

This paragraph is different from the corresponding paragraph in the other document.
The words in this sentence are arranged in an alternative way that produces a diff.

Additional content ensures this document is long enough to be scrollable in the panel.
The panel body must be able to scroll for the scroll sync test to verify movement.
More text here to increase the document height beyond the viewport panel height.
Even more content so the scrollHeight clearly exceeds the clientHeight of the panel body element.
The diff viewer needs the panels to actually scroll for any scroll sync assertion to be meaningful.
This paragraph adds further length to guarantee scrollability across all viewport configurations.

The document continues with more paragraphs to ensure the rendered height is substantial.
Each paragraph adds approximately two line-heights of rendered content to the total height.
With a typical viewport of 720px and a topbar of 44px, the panel body is around 676px tall.
At 13px font with 1.75 line-height the rendered line height is approximately 23px.
This means we need roughly 30 lines of content to guarantee the document exceeds panel height.

Paragraph twenty-one adds more content to push past any reasonable viewport height constraint.
The text here is meaningless filler whose only purpose is to increase the rendered document height.
Scroll sync tests are sensitive to whether panels can scroll; short fixtures silently break them.
A fixture that is too short produces a scrollHeight equal to clientHeight and maxScroll of zero.
When maxScroll is zero the anchor builder exits early and leaves scrollAnchors empty.
Empty anchors cause the scroll listener to use percentage-based fallback, which still requires scrollable panels.

This block of paragraphs ensures the document is definitively taller than any test viewport.
The content wraps at the panel max-width of 820px so line counts map reasonably to pixel heights.
With padding of 28px top and 36px sides each paragraph occupies at least one rendered line.
Adding a safety margin of 50 percent above the minimum means the fixture is reliably scrollable.
These final lines bring the total well past the required threshold for all headless browser modes.

End of no-headings-b fixture. This document intentionally contains no Markdown headings.

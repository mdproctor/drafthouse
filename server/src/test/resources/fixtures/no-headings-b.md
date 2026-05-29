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
This final paragraph ensures we have comfortably exceeded the minimum required document length.

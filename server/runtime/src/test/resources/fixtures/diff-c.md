# Document C

This is a third test document for the doc picker.

## Introduction

This introduction paragraph is unique to document C.
It differs from both documents A and B in content.
The structure is similar but the words are completely different.

## Unique Section

Content that appears only in document C.
This section has no counterpart in either document A or document B.
It ensures that when diff-c is compared to diff-b, there will be visible differences.

## Another Section

This paragraph contains entirely different text from the other documents.
The word choices, sentence structure, and content are all unique to document C.
This ensures the LCS diff algorithm will find differences when comparing C to B.

## Testing Notes

When document C is set as the A-side and compared to document B,
the diff viewer should find multiple diff chunks between the two files.
This allows the E2E test to verify that reassigning the comparison works correctly.

Additional lines of text make the scrollHeight exceed the clientHeight of the panel body.
Without scrollable content the scroll sync tests cannot verify that panels actually move.
More content here to ensure the panel is tall enough for testing purposes.

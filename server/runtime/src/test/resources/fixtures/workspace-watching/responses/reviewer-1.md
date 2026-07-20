## R1-01: Missing error handling for null input

The `processInput()` method does not check for null values.
This will cause a NullPointerException at runtime.

SIGNAL: CONTINUE

# Timeline Fixtures

This directory contains real captured wire frames for `FixtureReplayTest`. These allow us to test the `reduceStreamFrame` and `mergeServerMessages` logic against actual data that hit the device.

## How to capture new fixtures

You can capture new fixtures using `app-server-iroh-probe`:

```bash
app-server-iroh-probe --dump-frames > raw_capture.jsonl
```

## Fixture Format (.jsonl)

Each fixture is a JSONL (JSON Lines) file.

- **Line 1 (Header):** Metadata describing the test expectations.
  ```json
  {
    "name": "fixture-name",
    "capture_date": "YYYY-MM-DD",
    "bug_id": "optional-bug-id",
    "expected_final_row_count": 1,
    "expected_assistant_text": "The expected concatenated text"
  }
  ```
- **Line 2+ (Frames):** The raw JSON payloads for the messages, one per line.

# Timeline Fixtures

This directory contains real captured wire frames for `FixtureReplayTest`. These allow us to test the `reduceStreamFrame` logic against actual data that hit the device.

## How to capture new fixtures

You can capture new fixtures using `app-server-iroh-probe`:

```bash
./gradlew :cli:run --args="app-server-iroh-probe --backend <iroh-ticket> --dump-frames raw_capture.jsonl"
```

The probe takes a file path for `--dump-frames`; stdout is not a fixture stream.
Prepare the expectation header manually and ensure each following line decodes as
`LettaMessage`, as `FixtureReplayTest.runFixture` requires. The test does not
consume arbitrary transport envelopes or exercise history merging.

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

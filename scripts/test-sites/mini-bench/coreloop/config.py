from pathlib import Path

SERIAL = "711KPAE0914240"

PACKAGE = "com.letta.mobile.dev"

SCENARIO_TIMEOUTS = {"S1": 75, "S2": 150, "S3": 75, "S4": 75}

HERE = Path(__file__).resolve().parent.parent


def trial_prompt(metadata, follow_up=False):
    """Identify one send within a capture; legacy evidence has no trial identity."""
    capture = metadata.get("captureId")
    if not isinstance(capture, str) or not capture.strip():
        return ""
    kind = "follow-up" if follow_up else "trial"
    return f"[core-loop:{metadata['scenario']}] {kind}-{metadata['trial']}-{capture}"

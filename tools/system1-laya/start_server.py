#!/usr/bin/env python3
"""Launcher for the System 1 (Laya) service.

Loads the model, runs a warm-up forward pass, then serves on 127.0.0.1:8771.
The service binds to loopback by default: it is an in-process-speed dependency
of the local app, not a network service, and its `/predict` endpoint will
happily evaluate any question you hand it.

    python start_server.py                    # auto device, warm start
    python start_server.py --device cpu       # force CPU
    python start_server.py --port 9000        # alternate port
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import uvicorn  # noqa: E402

from server import build_arg_parser, create_app  # noqa: E402


def main() -> int:
    args = build_arg_parser().parse_args()
    app = create_app(model_id=args.model, device=args.device, warmup=args.warmup)
    print(f"[system1] starting on http://{args.host}:{args.port} (model={args.model})")
    uvicorn.run(app, host=args.host, port=args.port, log_level="info")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

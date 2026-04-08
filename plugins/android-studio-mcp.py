#!/usr/bin/env python3
"""
stdio-to-SSE bridge for Android Studio MCP server.

Connects to the MCP SSE endpoint running inside Android Studio and translates
between stdio (JSON-RPC over stdin/stdout) and HTTP SSE. This allows CLI-based
MCP clients like Claude Code, OpenCode, and others to communicate with the
Android Studio plugin.

Usage:
    python android-studio-mcp.py [--port PORT]

Requires Python 3.7+ with no external dependencies.
"""

import argparse
import http.client
import io
import os
import signal
import sys
import threading


def sse_reader(host, port, endpoint_ready, endpoint_holder, shutdown):
    """Read the SSE stream, extract the session endpoint, and forward JSON-RPC responses to stdout."""
    try:
        conn = http.client.HTTPConnection(host, port, timeout=None)
        conn.request("GET", "/sse", headers={"Accept": "text/event-stream"})
        resp = conn.getresponse()

        buf = b""
        while not shutdown.is_set():
            chunk = resp.read(1)
            if not chunk:
                break
            buf += chunk
            if chunk != b"\n":
                continue

            line = buf.decode("utf-8", errors="replace").rstrip("\r\n")
            buf = b""

            if not line.startswith("data: "):
                continue

            data = line[6:]

            if data.startswith("/message?sessionId="):
                endpoint_holder.append(data)
                endpoint_ready.set()
            else:
                sys.stdout.write(data + "\n")
                sys.stdout.flush()
    except Exception as e:
        if not shutdown.is_set():
            sys.stderr.write(f"SSE connection error: {e}\n")
            sys.stderr.flush()
    finally:
        shutdown.set()
        endpoint_ready.set()


def stdin_reader(host, port, endpoint_ready, endpoint_holder, shutdown):
    """Read JSON-RPC requests from stdin and POST them to the session endpoint."""
    if not endpoint_ready.wait(timeout=10):
        sys.stderr.write("Timed out waiting for session endpoint from SSE stream.\n")
        sys.stderr.flush()
        shutdown.set()
        return

    if not endpoint_holder:
        sys.stderr.write("No session endpoint received.\n")
        sys.stderr.flush()
        shutdown.set()
        return

    endpoint = endpoint_holder[0]

    # Use readline() instead of iterating sys.stdin to avoid buffering delays.
    # Python's `for line in sys.stdin` uses a read-ahead buffer that can hold
    # lines until the buffer fills, causing MCP clients to hang.
    while not shutdown.is_set():
        line = sys.stdin.readline()
        if not line:
            break
        line = line.strip()
        if not line:
            continue
        try:
            conn = http.client.HTTPConnection(host, port, timeout=30)
            conn.request(
                "POST",
                endpoint,
                body=line.encode("utf-8"),
                headers={"Content-Type": "application/json"},
            )
            conn.getresponse().read()
            conn.close()
        except Exception as e:
            if not shutdown.is_set():
                sys.stderr.write(f"POST error: {e}\n")
                sys.stderr.flush()

    shutdown.set()


def main():
    # Force unbuffered stdout so JSON-RPC responses are sent immediately.
    # This is critical for stdio-based MCP clients.
    sys.stdout = io.TextIOWrapper(
        open(sys.stdout.fileno(), "wb", buffering=0),
        write_through=True,
    )

    parser = argparse.ArgumentParser(description="stdio-to-SSE bridge for Android Studio MCP server")
    parser.add_argument("--port", type=int, default=24601, help="MCP server port (default: 24601)")
    args = parser.parse_args()

    host = "127.0.0.1"
    shutdown = threading.Event()
    endpoint_ready = threading.Event()
    endpoint_holder = []

    def handle_signal(sig, frame):
        shutdown.set()

    signal.signal(signal.SIGINT, handle_signal)
    if hasattr(signal, "SIGTERM"):
        signal.signal(signal.SIGTERM, handle_signal)

    sse_thread = threading.Thread(
        target=sse_reader,
        args=(host, args.port, endpoint_ready, endpoint_holder, shutdown),
        daemon=True,
    )
    sse_thread.start()

    stdin_reader(host, args.port, endpoint_ready, endpoint_holder, shutdown)


if __name__ == "__main__":
    main()

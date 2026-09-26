"""FastAPI service exposing Laya as Letta Mobile's System 1 layer.

System 1 is the fast, non-autoregressive half of the dual-process split: it
answers calibrated yes/no, choice and score questions about a user message in a
single forward pass (~55-70ms on CUDA, ~150-250ms on CPU) so the app can decide
whether System 2 — the Letta agent turn loop — needs to run at all.

Endpoints
---------
GET  /health                  model status, device, memory, uptime
POST /predict                 arbitrary Laya state + questions passthrough
POST /v1/system1/guard        jailbreak / injection / sensitive-data / harm / topic
POST /v1/system1/route        difficulty / domain / tool need / sensitivity
POST /v1/system1/triage       intent / urgency / frustration / churn risk
POST /v1/system1/interaction  composer turn-taking: done typing? expects a reply?
POST /v1/system1/evaluate     all of the above in ONE batched forward pass

Run with `python start_server.py`, not by importing this module directly.
"""

import argparse
import os
from contextlib import asynccontextmanager
from typing import Any, Dict, List, Optional

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool

from questions import combined_questions, interaction_questions, question_set, unified_state
from runtime import (
    System1Runtime,
    Thresholds,
    decide,
    guard_report,
    interaction_report,
    route_report,
    triage_report,
)

DEFAULT_MODEL = os.environ.get("SYSTEM1_MODEL", "convaiinnovations/laya")
# 8771 avoids Letta's own 8283 and the conventional 8000.
DEFAULT_PORT = int(os.environ.get("SYSTEM1_PORT", "8771"))

_state: Dict[str, Any] = {"runtime": None, "thresholds": Thresholds.from_env()}


def runtime() -> System1Runtime:
    rt = _state.get("runtime")
    if rt is None:
        raise HTTPException(status_code=503, detail="System 1 model is not loaded yet")
    return rt


class EvaluateRequest(BaseModel):
    """Common request shape for every `/v1/system1/*` endpoint.

    `text` is the user message (or live composer draft). `conversation` and
    `context` are optional extra state Laya reads alongside it;
    `previous_draft` only matters to the interaction set.
    """

    text: str = Field(..., description="User message or live composer draft")
    conversation: Optional[List[Any]] = Field(default=None, description="Recent turns, oldest first")
    previous_draft: Optional[str] = Field(default=None, description="Draft at the last evaluation")
    context: Optional[Dict[str, Any]] = Field(default=None, description="Extra state fields")
    thresholds: Optional[Dict[str, float]] = Field(
        default=None, description="Per-request threshold overrides"
    )


class PredictRequest(BaseModel):
    """Raw Laya passthrough for callers that bring their own question set."""

    state: Any = Field(..., description="String, dict or turn-list state")
    questions: Dict[str, Dict[str, Any]] = Field(..., description="question_id -> definition")


@asynccontextmanager
async def lifespan(app: FastAPI):
    model_id = app.state.model_id
    device = app.state.device
    _state["runtime"] = System1Runtime(model_id, device=device)
    if app.state.warmup:
        # First forward pass pays kernel autotuning and lazy CUDA init. Doing it
        # here keeps that cost off the first real request.
        await run_in_threadpool(
            _state["runtime"].evaluate,
            unified_state("warmup"),
            question_set("guard"),
        )
    yield
    _state["runtime"] = None


def create_app(
    model_id: str = DEFAULT_MODEL,
    device: Optional[str] = None,
    warmup: bool = True,
) -> FastAPI:
    app = FastAPI(
        title="Letta Mobile System 1 (Laya)",
        description="Fast calibrated decision layer in front of the Letta agent turn loop.",
        version="1.0.0",
        lifespan=lifespan,
    )
    app.state.model_id = model_id
    app.state.device = device
    app.state.warmup = warmup

    register_endpoints(app)
    return app


def register_endpoints(app: FastAPI) -> None:
    @app.get("/health")
    async def health() -> JSONResponse:
        rt = _state.get("runtime")
        if rt is None:
            return JSONResponse(status_code=503, content={"status": "loading"})
        return JSONResponse(content=rt.health())

    @app.post("/predict")
    async def predict(req: PredictRequest) -> Dict[str, Any]:
        if not req.questions:
            raise HTTPException(status_code=422, detail="questions must not be empty")
        try:
            return await run_in_threadpool(runtime().evaluate, req.state, req.questions)
        except (KeyError, ValueError) as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

    async def _run(req: EvaluateRequest, name: str) -> Dict[str, Any]:
        state = unified_state(req.text, req.conversation, req.previous_draft, req.context)
        return await run_in_threadpool(runtime().evaluate, state, question_set(name))

    @app.post("/v1/system1/guard")
    async def guard(req: EvaluateRequest) -> Dict[str, Any]:
        result = await _run(req, "guard")
        return {"guard": guard_report(result["answers"]), "latency_ms": result["latency_ms"]}

    @app.post("/v1/system1/route")
    async def route(req: EvaluateRequest) -> Dict[str, Any]:
        result = await _run(req, "route")
        return {"route": route_report(result["answers"]), "latency_ms": result["latency_ms"]}

    @app.post("/v1/system1/triage")
    async def triage(req: EvaluateRequest) -> Dict[str, Any]:
        result = await _run(req, "triage")
        return {"triage": triage_report(result["answers"]), "latency_ms": result["latency_ms"]}

    @app.post("/v1/system1/interaction")
    async def interaction(req: EvaluateRequest) -> Dict[str, Any]:
        result = await _run(req, "interaction")
        return {
            "interaction": interaction_report(result["answers"]),
            "latency_ms": result["latency_ms"],
        }

    @app.post("/v1/system1/evaluate")
    async def evaluate(req: EvaluateRequest) -> Dict[str, Any]:
        """Every question set in one batched forward pass, plus a disposition.

        Laya batches all questions into a single model call, so the combined
        assessment costs ~70ms rather than four round trips.
        """
        state = unified_state(req.text, req.conversation, req.previous_draft, req.context)
        questions = combined_questions(["guard", "route", "triage", "interaction"])
        result = await run_in_threadpool(runtime().evaluate, state, questions)
        answers = result["answers"]

        guard = guard_report(answers, "guard.")
        route = route_report(answers, "route.")
        triage = triage_report(answers, "triage.")
        interaction = interaction_report(answers, "interaction.")
        thresholds = _state["thresholds"].merged_with(req.thresholds)
        verdict = decide(guard, route, thresholds)

        return {
            "disposition": verdict["disposition"],
            "reason": verdict["reason"],
            "guard": guard,
            "route": route,
            "triage": triage,
            "interaction": interaction,
            "latency_ms": result["latency_ms"],
            "model": result.get("model", "laya-rl-agent"),
            "input_tokens": result.get("usage", {}).get("input_tokens", 0),
        }

    @app.get("/v1/system1/questions")
    async def questions() -> Dict[str, Any]:
        """The question sets this service evaluates, for debugging and docs."""
        return {
            "guard": question_set("guard"),
            "route": question_set("route"),
            "triage": question_set("triage"),
            "interaction": interaction_questions(),
        }



def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Letta Mobile System 1 (Laya) service")
    parser.add_argument("--host", default=os.environ.get("SYSTEM1_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument(
        "--device",
        default=os.environ.get("SYSTEM1_DEVICE"),
        help="cuda, cpu or mps. Omit to auto-select (CUDA preferred, then MPS, then CPU).",
    )
    parser.add_argument(
        "--no-warmup",
        dest="warmup",
        action="store_false",
        help="Skip the startup forward pass (first request then pays JIT/autotune cost).",
    )
    parser.set_defaults(warmup=True)
    return parser

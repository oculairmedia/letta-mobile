------------------------------- MODULE MC -------------------------------
(* Scenarios for TimelineIdentity. Each mirrors a fixture of              *)
(* TimelineRunKeyAcrossPagesTest (PR #2480), shrunk to seven rows.          *)
EXTENDS TimelineIdentity

\* (a) one multi-step run, a step closed by opaque usage/stop_reason rows:
\*     prompt, call, narration, usage, call-2, reply, stop_reason
ScenarioA == <<"P", "R", "R", "U", "R", "R", "U">>
RunsA     == <<"",  "r", "r", "r", "r", "r", "r">>

\* (b) the #2460 shape: a run-less follow-up prompt lands inside the run:
\*     prompt, call, narration, follow-up, call-2, reply, stop_reason
ScenarioB == <<"P", "R", "R", "P", "R", "R", "U">>
RunsB     == <<"",  "r", "r", "",  "r", "r", "r">>

\* (c) control: two ordinary turns, each one step closed by its usage row:
\*     prompt, reply, reply, usage | prompt, reply, reply
ScenarioC == <<"P", "R", "R", "U", "P", "R", "R">>
RunsC     == <<"",  "r", "r", "r", "",  "s", "s">>
=============================================================================

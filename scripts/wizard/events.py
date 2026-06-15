from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Any, TextIO

from .model import StepResult
from .state import append_jsonl, step_result_payload


class EventSink:
    def __init__(self, *, jsonl_path: Path, stdout_json: bool = False, stream: TextIO | None = None) -> None:
        self.jsonl_path = jsonl_path
        self.stdout_json = stdout_json
        self.stream = stream or sys.stdout
        self._stdout_closed = False

    def emit(self, event: dict[str, Any]) -> None:
        append_jsonl(self.jsonl_path, event)
        if self.stdout_json and not self._stdout_closed:
            try:
                self.stream.write(json.dumps(event, sort_keys=True, default=str) + "\n")
                self.stream.flush()
            except BrokenPipeError:
                self._stdout_closed = True
                try:
                    devnull = open(os.devnull, "w", encoding="utf-8")
                    self.stream = devnull
                    sys.stdout = devnull
                except OSError:
                    pass

    def step_started(self, step_id: str, title: str, *, log_path: str | None = None) -> None:
        event = {"type": "step_started", "step": step_id, "title": title}
        if log_path:
            event["log_path"] = log_path
        self.emit(event)

    def step_log(self, step_id: str, message: str) -> None:
        self.emit({"type": "log", "step": step_id, "message": message})

    def step_finished(self, result: StepResult) -> None:
        payload = step_result_payload(result)
        payload["type"] = "step_finished"
        payload["step"] = result.id
        self.emit(payload)

    def blocked(self, result: StepResult) -> None:
        self.emit(
            {
                "type": "blocked",
                "step": result.id,
                "status": str(result.status),
                "summary": result.summary,
                "next_action": result.next_action,
                "evidence": result.evidence,
            }
        )

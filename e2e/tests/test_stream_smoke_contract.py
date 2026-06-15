#!/usr/bin/env python3
from __future__ import annotations

import unittest
import os
from pathlib import Path


class StreamSmokeContractTests(unittest.TestCase):
    def test_guest_stream_smoke_has_failure_summary_contract(self) -> None:
        script = Path(__file__).resolve().parent.parent / "scripts" / "guest-stream-smoke.sh"
        self.assertTrue(os.access(script, os.X_OK))
        text = script.read_text(encoding="utf-8")
        self.assertIn("write_summary", text)
        self.assertIn("stream_tcp_no_bytes", text)
        self.assertIn("portal_consent_required", text)
        self.assertIn("portal_unavailable", text)
        self.assertIn("graphical_session_missing", text)
        self.assertIn("session-probe.json", text)
        self.assertIn("portal-probe.json", text)
        self.assertIn("pipewire-probe.json", text)
        self.assertIn("client.json", text)
        self.assertIn("status-after-start", text)
        self.assertIn("reason_code", text)
        self.assertIn("blocked", text)


if __name__ == "__main__":
    unittest.main()

import asyncio
import os
import unittest

import numpy as np


class ServerClaudeDisabledTest(unittest.TestCase):
    def test_server_claude_status_stays_disabled_even_when_api_key_exists(self):
        os.environ["ANTHROPIC_API_KEY"] = "test-key"
        from home_server import server

        status = server._claude_status()

        self.assertFalse(status["enabled"])
        self.assertEqual(status["mode"], "app-only")

    def test_server_does_not_call_claude_inspector_for_alarm_results(self):
        from home_server import server

        class ExplodingInspector:
            def inspect_if_due(self, **kwargs):
                raise AssertionError("server should not call Claude")

        server.claude_inspector = ExplodingInspector()
        image = np.zeros((40, 40, 3), dtype=np.uint8)
        result = {
            "alarm": True,
            "status": "NG",
            "detections": [
                {
                    "box": [5, 5, 30, 30],
                    "score": 0.9,
                    "foot_point": [20, 30],
                    "in_area": True,
                }
            ],
        }

        asyncio.run(
            server._maybe_run_claude_inspection(
                img_np=image,
                detections=result["detections"],
                result=result,
                ts="20260101_000000",
                uid="unit",
                source="test",
                force=True,
            )
        )

        self.assertNotIn("claude_inspection", result)

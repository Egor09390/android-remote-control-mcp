import json
import unittest
from unittest.mock import AsyncMock, patch

import httpx

import bridge


class BridgeTest(unittest.IsolatedAsyncioTestCase):
    token = "a" * 64
    serial = "127.0.0.1:31001"
    trace = "b59fe34e-8e46-4e40-a8e4-01da4c4c84ba"

    def setUp(self):
        self.app = bridge.create_app(self.token, self.serial)

    async def request(self, name, arguments=None, auth=True):
        headers = {"Authorization": f"Bearer {self.token}"} if auth else {}
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app), base_url="http://test") as client:
            return await client.post("/tool", json={"name": name, "arguments": arguments or {}}, headers=headers)

    async def test_requires_token_and_limits_tools(self):
        self.assertEqual((await self.request("artemis_status", auth=False)).status_code, 401)
        self.assertTrue((await self.request("mobile_diagnose")).json()["isError"])

    @patch("bridge.mobile_run_task", return_value={"trace_id": trace})
    @patch("bridge.device_status", return_value={"ready": True})
    async def test_task_uses_paired_phone(self, _status, upstream):
        response = await self.request("artemis_run_task", {"task_desc": "Read Android version"})
        self.assertEqual(json.loads(response.json()["content"][0]["text"]), {"trace_id": self.trace})
        self.assertEqual(upstream.call_args.kwargs["device_serial"], self.serial)
        response = await self.request("artemis_run_task", {"task_desc": "Read Android version", "device_serial": "other"})
        self.assertTrue(response.json()["isError"])
        upstream.assert_called_once()

    @patch("bridge.mobile_run_task")
    @patch("bridge.device_status", return_value={"ready": False, "error": "ADB is disconnected."})
    async def test_disconnected_phone_does_not_start_a_task(self, _status, upstream):
        response = await self.request("artemis_run_task", {"task_desc": "Read Android version"})
        self.assertTrue(response.json()["isError"])
        upstream.assert_not_called()

    @patch("bridge.mobile_manage_task", return_value={"status": "completed"})
    async def test_manage_rejects_paths_in_trace_id(self, upstream):
        self.assertTrue((await self.request("artemis_manage_task", {"action": "status", "trace_id": "../secret"})).json()["isError"])
        response = await self.request("artemis_manage_task", {"action": "status", "trace_id": self.trace})
        self.assertFalse(response.json()["isError"])
        upstream.assert_called_once_with(action="status", trace_id=self.trace)

    @patch("bridge.mobile_inspect_trace", new_callable=AsyncMock, return_value="Trace summary")
    async def test_inspects_trace(self, upstream):
        response = await self.request("artemis_inspect_trace", {"action": "view_summary", "trace_id": self.trace})
        self.assertFalse(response.json()["isError"])
        self.assertEqual(response.json()["content"][0]["text"], "Trace summary")
        upstream.assert_awaited_once_with(action="view_summary", trace_id=self.trace, query=None)


if __name__ == "__main__":
    unittest.main()

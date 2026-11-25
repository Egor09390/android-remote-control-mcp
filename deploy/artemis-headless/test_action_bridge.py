import base64
from io import BytesIO
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, patch

import httpx
from PIL import Image

import action_bridge


class ActionBridgeTest(unittest.IsolatedAsyncioTestCase):
    token = "a" * 64

    def setUp(self):
        self.app = action_bridge.create_app(self.token, "127.0.0.1:15575")

    async def request(self, name, arguments=None, authorized=True):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app), base_url="http://test") as client:
            headers = {"Authorization": f"Bearer {self.token}"} if authorized else {}
            return await client.post("/tool", json={"name": name, "arguments": arguments or {}}, headers=headers)

    @patch("action_bridge.actuator_for")
    async def test_no_agent_prompts_or_model_selection(self, actuator):
        self.assertEqual((await self.request("artemis_tap", authorized=False)).status_code, 401)
        self.assertTrue((await self.request("artemis_run_task", {"task_desc": "Open Settings"})).json()["isError"])
        self.assertTrue((await self.request("artemis_tap", {"x": 2, "y": 3, "model": "gemini"})).json()["isError"])
        actuator.assert_not_called()

    @patch("action_bridge.actuator_for")
    async def test_forwards_a_coordinate_tap(self, actuator):
        actuator.return_value.click = AsyncMock(return_value=SimpleNamespace(ok=True, message="Tapped."))
        invalid = await self.request("artemis_tap", {"x": 1001, "y": 100})
        self.assertTrue(invalid.json()["isError"])
        actuator.assert_not_called()
        response = await self.request("artemis_tap", {"x": 500, "y": 100})
        self.assertFalse(response.json()["isError"])
        actuator.assert_called_once_with("127.0.0.1:15575")
        actuator.return_value.click.assert_awaited_once_with(500, 100)

    @patch("action_bridge.actuator_for")
    async def test_returns_an_mcp_image(self, actuator):
        image = BytesIO()
        Image.new("RGB", (1200, 2400)).save(image, "JPEG")
        actuator.return_value.take_screenshot = AsyncMock(return_value=base64.b64encode(image.getvalue()).decode())
        response = await self.request("artemis_screen")
        content = response.json()["content"][1]
        self.assertEqual(content["type"], "image")
        self.assertEqual(content["mimeType"], "image/jpeg")
        with Image.open(BytesIO(base64.b64decode(content["data"]))) as resized:
            self.assertEqual(resized.size, (900, 1800))

    @patch("action_bridge.actuator_for")
    async def test_typing_preserves_existing_text(self, actuator):
        actuator.return_value.input_text = AsyncMock(return_value=SimpleNamespace(ok=True))
        response = await self.request("artemis_type_text", {"text": "Привет"})
        self.assertFalse(response.json()["isError"])
        actuator.return_value.input_text.assert_awaited_once_with("Привет", clear_exist=False)

    @patch("action_bridge.actuator_for")
    async def test_swipe_and_key_are_single_actions(self, actuator):
        outcome = SimpleNamespace(ok=True, message="Done.")
        actuator.return_value.swipe = AsyncMock(return_value=outcome)
        actuator.return_value.press_key = AsyncMock(return_value=outcome)
        swipe = await self.request("artemis_swipe", {"x1": 400, "y1": 800, "x2": 400, "y2": 300})
        self.assertFalse(swipe.json()["isError"])
        actuator.return_value.swipe.assert_awaited_once_with((400, 800), (400, 300), 350)
        self.assertTrue((await self.request("artemis_press_key", {"key": "power"})).json()["isError"])
        self.assertFalse((await self.request("artemis_press_key", {"key": "back"})).json()["isError"])
        actuator.return_value.press_key.assert_awaited_once_with("back")


if __name__ == "__main__":
    unittest.main()

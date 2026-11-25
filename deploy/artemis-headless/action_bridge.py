"""Expose device actions from ARTEMIS to the model in the remote MCP client."""

from __future__ import annotations

import argparse
import asyncio
import base64
import io
import os
from pathlib import Path
import re
from secrets import compare_digest
from typing import Annotated, Literal

from artemis.mcp.adb_server import _get_controller
from artemis.mcp.actuators.adb import AdbActuator
from fastapi import FastAPI, Request, Response
from mcp.types import CallToolResult, ImageContent, TextContent
from PIL import Image
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from starlette.concurrency import run_in_threadpool
import uvicorn

Coordinate = Annotated[int, Field(ge=0, le=1000)]


class Arguments(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class Tap(Arguments):
    x: Coordinate
    y: Coordinate


class Swipe(Arguments):
    x1: Coordinate
    y1: Coordinate
    x2: Coordinate
    y2: Coordinate
    duration_ms: Annotated[int, Field(ge=100, le=3000)] = 350


class TypeText(Arguments):
    text: Annotated[str, Field(min_length=1, max_length=2000)]


class PressKey(Arguments):
    key: Literal["back", "home", "enter"]


class ToolRequest(Arguments):
    name: Literal[
        "artemis_status", "artemis_screen", "artemis_tap", "artemis_swipe", "artemis_type_text", "artemis_press_key"
    ]
    arguments: dict = Field(default_factory=dict)


def result(text: str, *, error: bool = False) -> CallToolResult:
    return CallToolResult(content=[TextContent(type="text", text=text)], isError=error)


def actuator_for(serial: str) -> AdbActuator:
    controller = _get_controller(device_serial=serial)
    return AdbActuator(controller.ctx, controller)


def screenshot(encoded: str) -> CallToolResult:
    with Image.open(io.BytesIO(base64.b64decode(encoded, validate=True))) as image:
        image.thumbnail((1000, 1800))
        output = io.BytesIO()
        image.convert("RGB").save(output, "JPEG", quality=72)
    return CallToolResult(content=[
        TextContent(type="text", text="Current Android screen. Map coordinates to 0–1000 on each axis."),
        ImageContent(type="image", mimeType="image/jpeg", data=base64.b64encode(output.getvalue()).decode("ascii")),
    ])


def validate_args(call: ToolRequest) -> Arguments:
    schemas = {
        "artemis_tap": Tap,
        "artemis_swipe": Swipe,
        "artemis_type_text": TypeText,
        "artemis_press_key": PressKey,
    }
    return schemas.get(call.name, Arguments).model_validate(call.arguments)


async def perform(call: ToolRequest, args: Arguments, actuator: AdbActuator) -> CallToolResult:
    match args:
        case Tap():
            outcome = await actuator.click(args.x, args.y)
        case Swipe():
            outcome = await actuator.swipe((args.x1, args.y1), (args.x2, args.y2), args.duration_ms)
        case TypeText():
            outcome = await actuator.input_text(args.text, clear_exist=False)
            return result("Text entered." if outcome.ok else "Android could not enter the text.", error=not outcome.ok)
        case PressKey():
            outcome = await actuator.press_key(args.key)
        case _:
            if call.name == "artemis_screen":
                return await run_in_threadpool(screenshot, await actuator.take_screenshot())
            return result("ARTEMIS is ready and connected to this Android device.")
    return result(outcome.message if outcome.ok else "Android could not execute the action.", error=not outcome.ok)


def create_app(token: str, serial: str) -> FastAPI:
    if not re.fullmatch(r"[0-9a-f]{64}", token):
        raise ValueError("Invalid local ARTEMIS token")
    if serial != "127.0.0.1:15575":
        raise ValueError("Expected the private loopback Android transport")
    authorization = f"Bearer {token}".encode()
    app = FastAPI(openapi_url=None, docs_url=None, redoc_url=None)
    actions = asyncio.Lock()

    @app.post("/tool")
    async def tool(request: Request) -> Response:
        if not compare_digest(request.headers.get("authorization", "").encode(), authorization):
            return Response(status_code=401)
        body = bytearray()
        async for chunk in request.stream():
            if len(body) + len(chunk) > 65_536:
                return Response(status_code=413)
            body.extend(chunk)
        try:
            call = ToolRequest.model_validate_json(body)
            args = validate_args(call)
            async with actions:
                actuator = await run_in_threadpool(actuator_for, serial)
                answer = await perform(call, args, actuator)
        except ValidationError:
            answer = result("Invalid arguments for an ARTEMIS device action.", error=True)
        except Exception:
            answer = result("ARTEMIS could not communicate with Android.", error=True)
        return Response(answer.model_dump_json(exclude_none=True), media_type="application/json")

    return app


def main() -> None:
    import sitecustomize

    parser = argparse.ArgumentParser()
    parser.add_argument("token_file", type=Path)
    args = parser.parse_args()
    if not getattr(sitecustomize, "private_adb_configured", False):
        parser.error("The private ADB socket must be configured.")
    uvicorn.run(
        create_app(args.token_file.read_text().strip(), os.environ["ADB_DEVICE_SERIAL"]),
        host="127.0.0.1", port=18765, proxy_headers=False, access_log=False,
    )


if __name__ == "__main__":
    main()

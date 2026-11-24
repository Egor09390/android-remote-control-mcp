"""Local HTTP adapter for the official ARTEMIS tools used by Android MCP."""

from __future__ import annotations

import argparse
import json
import logging
import os
from pathlib import Path
import re
from secrets import compare_digest
import subprocess
from typing import Annotated, Literal
from uuid import UUID

from fastapi import FastAPI, Request, Response
from mcp_server.tools import mobile_inspect_trace, mobile_manage_task, mobile_run_task
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from starlette.concurrency import run_in_threadpool
import uvicorn


class Arguments(BaseModel):
    model_config = ConfigDict(extra="forbid")


class RunTask(Arguments):
    task_desc: Annotated[str, Field(min_length=1, max_length=8000)]
    model: Literal["Flash", "Pro"] = "Flash"
    locked_app_package: Annotated[str, Field(pattern=r"^[a-zA-Z][a-zA-Z0-9_.]{0,254}$")] | None = None


class ManageTask(Arguments):
    trace_id: UUID
    action: Literal["status", "stop"]


class InspectTrace(Arguments):
    trace_id: UUID
    action: Literal["view_summary", "search"]
    query: Annotated[str, Field(min_length=1, max_length=500)] | None = None


class ToolRequest(Arguments):
    name: Literal["artemis_status", "artemis_run_task", "artemis_manage_task", "artemis_inspect_trace"]
    arguments: dict = Field(default_factory=dict)


def device_status(device_serial: str) -> dict:
    check = subprocess.run(
        [os.environ["ARTEMIS_ADB_PATH"], "-s", device_serial, "get-state"],
        capture_output=True,
        text=True,
        timeout=5,
        check=False,
    )
    ready = check.returncode == 0 and check.stdout.strip() == "device"
    return {"ready": ready, "device_serial": device_serial, "error": None if ready else "ADB is disconnected."}


async def call_tool(call: ToolRequest, device_serial: str) -> dict | str:
    if call.name == "artemis_status":
        Arguments.model_validate(call.arguments)
        return await run_in_threadpool(device_status, device_serial)
    if call.name == "artemis_run_task":
        args = RunTask.model_validate(call.arguments)
        state = await run_in_threadpool(device_status, device_serial)
        if not state["ready"]:
            return state
        return await run_in_threadpool(mobile_run_task, device_serial=device_serial, **args.model_dump())
    if call.name == "artemis_manage_task":
        args = ManageTask.model_validate(call.arguments)
        return await run_in_threadpool(mobile_manage_task, **args.model_dump(mode="json"))
    args = InspectTrace.model_validate(call.arguments)
    if args.action == "search" and args.query is None:
        return {"error": "Supply query when searching an ARTEMIS trace."}
    return await mobile_inspect_trace(**args.model_dump(mode="json"))


def tool_response(data: dict | str) -> Response:
    text = data if isinstance(data, str) else json.dumps(data, ensure_ascii=False)
    is_error = isinstance(data, dict) and bool(data.get("error"))
    body = json.dumps({"content": [{"type": "text", "text": text}], "isError": is_error},
                      ensure_ascii=False).encode()
    if len(body) > 1_000_000:
        return tool_response({"error": "ARTEMIS output is too large. Narrow the trace query."})
    return Response(body, media_type="application/json")


def create_app(token: str, device_serial: str) -> FastAPI:
    if not re.fullmatch(r"[0-9a-f]{64}", token):
        raise ValueError("Invalid local ARTEMIS token file.")
    if (not re.fullmatch(r"127\.0\.0\.1:[1-9][0-9]{0,4}", device_serial)
            or int(device_serial.rsplit(":", 1)[1]) > 65535):
        raise ValueError("The ADB device must be paired on this phone.")

    authorization = f"Bearer {token}".encode()
    app = FastAPI(openapi_url=None, docs_url=None, redoc_url=None)

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
            return tool_response(await call_tool(ToolRequest.model_validate_json(body), device_serial))
        except ValidationError:
            return tool_response({"error": "Invalid ARTEMIS tool arguments."})
        except Exception as exc:
            logging.getLogger(__name__).warning("ARTEMIS tool failed (%s)", type(exc).__name__)
            return tool_response({"error": "Local ARTEMIS failed to execute the task. Check Termux."})

    return app


def main() -> None:
    import sitecustomize

    parser = argparse.ArgumentParser()
    parser.add_argument("token_file", type=Path)
    args = parser.parse_args()
    if os.environ.get("ARTEMIS_STANDALONE") != "1" or not getattr(sitecustomize, "private_adb_configured", False):
        parser.error("Start the adapter through artemis.sh.")
    app = create_app(args.token_file.read_text().strip(), os.environ["ADB_DEVICE_SERIAL"])
    uvicorn.run(app, host="127.0.0.1", port=18765, proxy_headers=False, access_log=False)


if __name__ == "__main__":
    main()

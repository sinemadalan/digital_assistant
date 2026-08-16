from datetime import datetime, timezone
from collections import deque
import os
import json
from pathlib import Path
from typing import Any
import uvicorn
import time
from fastapi import FastAPI, Header, Request, HTTPException
from pydantic import BaseModel, Field, field_validator


app = FastAPI(title="Accessibility Capture Server")
CAPTURE_LOG_PATH = Path(__file__).with_name("captures.jsonl")
TARGET_APPS = {
    "com.instagram.android": "Instagram",
    "com.whatsapp": "WhatsApp",
    "com.facebook.katana": "Facebook",
}
SUPPORTED_EVENT_TYPES = {
    "TYPE_WINDOW_STATE_CHANGED",
    "TYPE_WINDOW_CONTENT_CHANGED",
}
MAX_NODE_COUNT = 100
MAX_TEXT_LENGTH = 500


class CaptureNode(BaseModel):
    text: str | None = Field(default=None, max_length=MAX_TEXT_LENGTH)
    content_description: str | None = Field(
        default=None,
        alias="contentDescription",
        max_length=MAX_TEXT_LENGTH,
    )
    class_name: str | None = Field(default=None, alias="className", max_length=200)
    view_id_resource_name: str | None = Field(
        default=None,
        alias="viewIdResourceName",
        max_length=300,
    )
    is_clickable: bool = Field(default=False, alias="isClickable")
    is_editable: bool = Field(default=False, alias="isEditable")


class CapturePayload(BaseModel):
    package_name: str = Field(alias="packageName")
    app_name: str = Field(alias="appName")
    event_type: str = Field(alias="eventType")
    captured_at_device: str | None = Field(default=None, alias="capturedAtDevice")
    screen_text: list[str] = Field(default_factory=list, alias="screenText")
    nodes: list[CaptureNode] = Field(default_factory=list, max_length=MAX_NODE_COUNT)

    @field_validator("screen_text")
    @classmethod
    def clean_screen_text(cls, value: list[str]) -> list[str]:
        cleaned: list[str] = []
        for item in value:
            text = item.strip()
            if text:
                cleaned.append(text[:MAX_TEXT_LENGTH])
        return cleaned

    @field_validator("nodes")
    @classmethod
    def keep_informative_nodes(cls, value: list[CaptureNode]) -> list[CaptureNode]:
        informative_nodes = [
            node for node in value if node.text or node.content_description
        ]
        return informative_nodes[:MAX_NODE_COUNT]


# For development only. Used to view Screenshots from device
class FileManager:
    def __init__(self, max_files=100):
        self.max_files = max_files
        self.file_queue = deque()
        self.save_directory = "saved_screenshots"

        # Create the directory if it doesn't exist
        os.makedirs(self.save_directory, exist_ok=True)

    def add_file(self, filepath: str):
        # Add the newest file to the right side of the queue
        self.file_queue.append(filepath)

        # If we have more than 100 files, remove the oldest one from the left
        while len(self.file_queue) > self.max_files:
            oldest_file = self.file_queue.popleft()

            # Explicitly delete the file from the hard drive
            if os.path.exists(oldest_file):
                os.remove(oldest_file)
                print(f"Deleted old file: {oldest_file}")


@app.get("/health")
def health_check() -> dict[str, str]:
    return {
        "status": "ok",
        "service": "accessibility-capture-server",
    }


# For development only. Used to view Screenshots from device
# Initialize our manager instance
file_manager = FileManager(max_files=100)


@app.post("/image_capture")
async def capture_image(request: Request, x_user_id: str = Header(default=None)):
    """
    FastAPI automatically looks for an HTTP header named 'X-User-Id'
    and maps it to the 'x_user_id' parameter.
    """

    # 1. Ensure the user ID was provided
    if not x_user_id:
        raise HTTPException(status_code=400, detail="X-User-Id header is missing")

    # 2. Read the raw image bytes from the request body
    image_bytes = await request.body()
    if not image_bytes:
        raise HTTPException(status_code=400, detail="No image payload received")
    print(f"Received screenshot: {len(image_bytes)} bytes")

    # 3. Create a unique filename using the User ID and the current timestamp
    filename = f"screenshot_{x_user_id}_{int(time.time())}.jpeg"
    filepath = os.path.join(file_manager.save_directory, filename)

    # 4. Save the JPEG bytes directly to disk
    with open(filepath, "wb") as file:
        file.write(image_bytes)

    # 5. Add the file path to our queue manager (which handles the 100-file limit)
    file_manager.add_file(filepath)

    # Return a JSON success response to the Android client
    return {"status": "success", "user": x_user_id, "saved_as": filename}


@app.post("/capture")
def capture(payload: CapturePayload) -> dict[str, Any]:
    received_at_server = datetime.now(timezone.utc).isoformat()
    record = payload.model_dump(by_alias=True)
    record["isTargetApp"] = payload.package_name in TARGET_APPS
    record["isSupportedEventType"] = payload.event_type in SUPPORTED_EVENT_TYPES
    record["receivedAtServer"] = received_at_server

    with CAPTURE_LOG_PATH.open("a", encoding="utf-8") as log_file:
        log_file.write(json.dumps(record, ensure_ascii=False) + "\n")

    return {
        "status": "received",
        "receivedAtServer": received_at_server,
        "packageName": payload.package_name,
        "appName": payload.app_name,
        "eventType": payload.event_type,
        "capturedAtDevice": payload.captured_at_device,
        "isTargetApp": record["isTargetApp"],
        "isSupportedEventType": record["isSupportedEventType"],
        "screenTextCount": len(payload.screen_text),
        "nodeCount": len(payload.nodes),
    }


@app.get("/captures")
def list_captures(limit: int = 20) -> dict[str, Any]:
    captures = read_captures(limit=limit)

    return {
        "count": len(captures),
        "captures": captures,
    }


def generate_summary(app_name: str, package_name: str) -> dict[str, Any]:
    """Helper function to generate a capture summary for a specific app."""
    captures = [
        capture
        for capture in read_captures()
        if capture.get("packageName") == package_name
    ]

    if not captures:
        return {
            "appName": app_name,
            "packageName": package_name,
            "captureCount": 0,
            "latestCapturedAtDevice": None,
            "latestReceivedAtServer": None,
            "averageScreenTextCount": 0,
            "averageNodeCount": 0,
            "latestScreenText": [],
            "latestNodeSamples": [],
        }

    latest_capture = captures[-1]
    screen_text_counts = [len(capture.get("screenText", [])) for capture in captures]
    node_counts = [len(capture.get("nodes", [])) for capture in captures]

    return {
        "appName": app_name,
        "packageName": package_name,
        "captureCount": len(captures),
        "latestCapturedAtDevice": latest_capture.get("capturedAtDevice"),
        "latestReceivedAtServer": latest_capture.get("receivedAtServer"),
        "averageScreenTextCount": round(
            sum(screen_text_counts) / len(screen_text_counts), 2
        ),
        "averageNodeCount": round(sum(node_counts) / len(node_counts), 2),
        "latestScreenText": latest_capture.get("screenText", [])[:20],
        "latestNodeSamples": latest_capture.get("nodes", [])[:10],
    }


@app.get("/captures/instagram/summary")
def instagram_capture_summary() -> dict[str, Any]:
    return generate_summary(app_name="Instagram", package_name="com.instagram.android")


@app.get("/captures/whatsapp/summary")
def whatsapp_capture_summary() -> dict[str, Any]:
    return generate_summary(app_name="WhatsApp", package_name="com.whatsapp")


def read_captures(limit: int | None = None) -> list[dict[str, Any]]:
    if not CAPTURE_LOG_PATH.exists():
        return []

    lines = CAPTURE_LOG_PATH.read_text(encoding="utf-8").splitlines()
    selected_lines = lines[-limit:] if limit is not None else lines
    return [json.loads(line) for line in selected_lines if line.strip()]


if __name__ == "__main__":
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)

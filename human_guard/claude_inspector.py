from __future__ import annotations

import base64
import json
import mimetypes
import os
import re
import threading
import time
import urllib.error
import urllib.request
import zipfile
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path
from typing import Any, Iterable, Optional
from xml.etree import ElementTree


CLAUDE_API_URL = "https://api.anthropic.com/v1/messages"
DEFAULT_CLAUDE_MODEL = os.getenv("CLAUDE_MODEL", "claude-sonnet-4-20250514")
DEFAULT_MAX_REFERENCE_CHARS = 12000
REFERENCE_IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".gif"}


@dataclass
class ReferenceDocument:
    name: str
    path: str
    size: int
    kind: str
    text_preview: str


class ReferenceStore:
    def __init__(self, root: Path, max_chars: int = DEFAULT_MAX_REFERENCE_CHARS) -> None:
        self.root = root
        self.max_chars = max_chars
        self.root.mkdir(exist_ok=True)

    def save_upload(self, filename: str, data: bytes) -> Path:
        safe_name = self._safe_filename(filename)
        target = self.root / safe_name
        stem = target.stem
        suffix = target.suffix
        counter = 1
        while target.exists():
            target = self.root / f"{stem}_{counter}{suffix}"
            counter += 1
        target.write_bytes(data)
        return target

    def list_documents(self) -> list[ReferenceDocument]:
        docs: list[ReferenceDocument] = []
        for path in sorted(self.root.iterdir()):
            if not path.is_file():
                continue
            text = self._extract_text(path)
            docs.append(
                ReferenceDocument(
                    name=path.name,
                    path=str(path),
                    size=path.stat().st_size,
                    kind="image" if self._is_image(path) else "document",
                    text_preview="" if self._is_image(path) else text[:500],
                )
            )
        return docs

    def image_references(self, limit: int = 5) -> list[dict[str, str]]:
        refs: list[dict[str, str]] = []
        for path in sorted(self.root.iterdir()):
            if not path.is_file() or not self._is_image(path):
                continue
            media_type = mimetypes.guess_type(path.name)[0] or "image/jpeg"
            refs.append(
                {
                    "name": path.name,
                    "media_type": media_type,
                    "base64": base64.b64encode(path.read_bytes()).decode("ascii"),
                }
            )
            if len(refs) >= limit:
                break
        return refs

    def combined_reference_text(self) -> str:
        parts: list[str] = []
        remaining = self.max_chars
        for path in sorted(self.root.iterdir()):
            if not path.is_file() or self._is_image(path) or remaining <= 0:
                continue
            text = self._extract_text(path).strip()
            if not text:
                continue
            block = f"[{path.name}]\n{text}"
            parts.append(block[:remaining])
            remaining -= len(parts[-1])
        return "\n\n".join(parts)

    def _extract_text(self, path: Path) -> str:
        if self._is_image(path):
            return ""
        suffix = path.suffix.lower()
        if suffix == ".docx":
            return self._extract_docx_text(path)

        data = path.read_bytes()
        try:
            return data.decode("utf-8")
        except UnicodeDecodeError:
            return data.decode("cp949", errors="ignore")

    def _extract_docx_text(self, path: Path) -> str:
        try:
            with zipfile.ZipFile(path) as archive:
                xml = archive.read("word/document.xml")
            root = ElementTree.fromstring(xml)
            texts = [node.text for node in root.iter() if node.tag.endswith("}t") and node.text]
            return "\n".join(texts)
        except Exception:
            return ""

    def _safe_filename(self, filename: str) -> str:
        name = Path(filename).name.strip() or "reference.txt"
        name = re.sub(r"[^A-Za-z0-9._ -]+", "_", name)
        return name[:120] or "reference.txt"

    def _is_image(self, path: Path) -> bool:
        return path.suffix.lower() in REFERENCE_IMAGE_SUFFIXES


class ClaudeInspector:
    def __init__(
        self,
        api_key: Optional[str] = None,
        model: str = DEFAULT_CLAUDE_MODEL,
        interval_seconds: float = 50.0,
    ) -> None:
        self.api_key = api_key or os.getenv("ANTHROPIC_API_KEY") or os.getenv("CLAUDE_API_KEY") or ""
        self.model = model
        self.interval_seconds = max(0.0, interval_seconds)
        self._lock = threading.Lock()
        self._last_call_at = 0.0

    @property
    def enabled(self) -> bool:
        return bool(self.api_key)

    def inspect_if_due(
        self,
        *,
        image_jpeg: bytes,
        source: str,
        yolo_status: str,
        detection_count: int,
        crop_count: int,
        reference_text: str = "",
        reference_images: Optional[list[dict[str, str]]] = None,
        force: bool = False,
    ) -> Optional[dict[str, Any]]:
        if not self.enabled:
            return {
                "skipped": True,
                "reason": "disabled",
                "message": "Server-side Claude inspection is disabled. Set ANTHROPIC_API_KEY or CLAUDE_API_KEY before starting the server.",
            }

        now = time.time()
        with self._lock:
            if not force and now - self._last_call_at < self.interval_seconds:
                return {
                    "skipped": True,
                    "reason": "cooldown",
                    "next_available_in_seconds": round(self.interval_seconds - (now - self._last_call_at), 1),
                }
            self._last_call_at = now

        return self.inspect(
            image_jpeg=image_jpeg,
            source=source,
            yolo_status=yolo_status,
            detection_count=detection_count,
            crop_count=crop_count,
            reference_text=reference_text,
            reference_images=reference_images or [],
        )

    def inspect(
        self,
        *,
        image_jpeg: bytes,
        source: str,
        yolo_status: str,
        detection_count: int,
        crop_count: int,
        reference_text: str = "",
        reference_images: Optional[list[dict[str, str]]] = None,
    ) -> dict[str, Any]:
        content = self._build_content(
            image_jpeg=image_jpeg,
            source=source,
            yolo_status=yolo_status,
            detection_count=detection_count,
            crop_count=crop_count,
            reference_text=reference_text,
            reference_images=reference_images or [],
        )
        payload = {
            "model": self.model,
            "max_tokens": 1200,
            "messages": [
                {
                    "role": "user",
                    "content": content,
                }
            ],
        }
        request = urllib.request.Request(
            CLAUDE_API_URL,
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "x-api-key": self.api_key,
                "anthropic-version": "2023-06-01",
                "content-type": "application/json",
            },
            method="POST",
        )

        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                body = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="ignore")
            return {
                "error": True,
                "status_code": exc.code,
                "message": self._parse_error_message(exc.code, body),
            }
        except Exception as exc:
            return {"error": True, "message": str(exc)}

        try:
            parsed_body = json.loads(body)
        except json.JSONDecodeError:
            return {"error": True, "message": "Claude response was not JSON", "raw_text": body[:1000]}

        raw_text = self._extract_response_text(parsed_body)
        parsed = self._parse_json_text(raw_text)
        if parsed is None:
            return {"error": True, "message": "Claude JSON parse failed", "raw_text": raw_text[:1000]}
        parsed["raw_text"] = raw_text
        return parsed

    def _build_content(
        self,
        *,
        image_jpeg: bytes,
        source: str,
        yolo_status: str,
        detection_count: int,
        crop_count: int,
        reference_text: str,
        reference_images: list[dict[str, str]],
    ) -> list[dict[str, Any]]:
        content: list[dict[str, Any]] = []
        for index, ref in enumerate(reference_images[:5], start=1):
            content.append(
                {
                    "type": "image",
                    "source": {
                        "type": "base64",
                        "media_type": ref.get("media_type") or "image/jpeg",
                        "data": ref["base64"],
                    },
                }
            )
            content.append(
                {
                    "type": "text",
                    "text": f"위 이미지는 참조 샘플 {index}입니다: {ref.get('name', 'reference image')}. 정상/불량 또는 기준 예시로 활용하세요.",
                }
            )

        content.append(
            {
                "type": "image",
                "source": {
                    "type": "base64",
                    "media_type": "image/jpeg",
                    "data": base64.b64encode(image_jpeg).decode("ascii"),
                },
            }
        )
        content.append(
            {
                "type": "text",
                "text": self._build_prompt(
                    source=source,
                    yolo_status=yolo_status,
                    detection_count=detection_count,
                    crop_count=crop_count,
                    reference_text=reference_text,
                    reference_image_count=len(reference_images),
                ),
            }
        )
        return content

    def _build_prompt(
        self,
        *,
        source: str,
        yolo_status: str,
        detection_count: int,
        crop_count: int,
        reference_text: str,
        reference_image_count: int,
    ) -> str:
        reference_block = (
            f"\n\n검사 기준 문서:\n{reference_text}"
            if reference_text.strip()
            else "\n\n검사 기준 문서는 아직 업로드되지 않았습니다. 일반 안전 감시 기준으로 판단하세요."
        )
        image_reference_block = (
            f"\n참조 이미지는 {reference_image_count}장 제공되었습니다. 참조 이미지는 앞쪽에 있으며, 마지막 이미지가 검사 대상입니다."
            if reference_image_count
            else "\n참조 이미지는 아직 업로드되지 않았습니다."
        )
        return f"""당신은 산업 현장 이미지 정밀 검사 보조자입니다.

YOLO가 사람을 발견한 영역을 크롭한 이미지가 제공됩니다. 사람 존재 여부, 위험 구역 침입, 작업 자세, 장비/차량/리프트 주변 위험, 오탐 가능성을 기준 문서와 함께 확인하세요.
추후 품질 검사에도 재사용할 수 있도록, 기준 문서가 있으면 문서의 항목과 이미지 증거를 연결해서 판단하세요.
Anomaly-site 방식처럼 앞쪽 참조 샘플과 마지막 검사 이미지를 비교하고, 기준 텍스트가 있으면 우선 적용하세요.

YOLO 요약:
- 출처: {source}
- YOLO 상태: {yolo_status}
- 감지 인원: {detection_count}
- Claude로 보낸 크롭 수: {crop_count}
{image_reference_block}
{reference_block}

반드시 JSON 하나만 한국어로 답하세요. 마크다운이나 설명 문장을 JSON 밖에 쓰지 마세요.
{{
  "status": "SAFE",
  "severity": "NONE",
  "summary": "한 문장 요약",
  "evidence": ["이미지에서 확인한 근거"],
  "reference_matches": ["기준 문서와 연결되는 항목"],
  "recommendations": ["필요 조치"],
  "false_positive_likely": false
}}

status는 SAFE, WATCH, DANGER 중 하나입니다.
severity는 NONE, LOW, MEDIUM, HIGH, CRITICAL 중 하나입니다.
확신이 낮으면 WATCH를 사용하고, 즉시 조치가 필요하면 DANGER를 사용하세요."""

    def _extract_response_text(self, body: dict[str, Any]) -> str:
        blocks = body.get("content") or []
        for block in blocks:
            if block.get("type") == "text":
                return str(block.get("text") or "")
        return ""

    def _parse_json_text(self, text: str) -> Optional[dict[str, Any]]:
        cleaned = text.strip()
        cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned, flags=re.IGNORECASE)
        cleaned = re.sub(r"\s*```$", "", cleaned)
        try:
            return json.loads(cleaned)
        except json.JSONDecodeError:
            match = re.search(r"\{.*\}", cleaned, flags=re.DOTALL)
            if not match:
                return None
            try:
                return json.loads(match.group(0))
            except json.JSONDecodeError:
                return None

    def _parse_error_message(self, status_code: int, body: str) -> str:
        try:
            parsed = json.loads(body)
            message = parsed.get("error", {}).get("message")
            if message:
                return str(message)
        except Exception:
            pass
        if status_code == 401:
            return "Claude API Key가 올바르지 않습니다."
        return f"Claude API 오류 ({status_code})"


def combine_crop_images(crops: Iterable[bytes]) -> bytes:
    from PIL import Image, ImageOps

    images = [Image.open(BytesIO(data)).convert("RGB") for data in crops]
    if not images:
        raise ValueError("No crop images provided")

    thumb_width = 512
    prepared = []
    for image in images[:6]:
        ratio = thumb_width / max(1, image.width)
        thumb = image.resize((thumb_width, max(1, int(image.height * ratio))))
        prepared.append(ImageOps.expand(thumb, border=8, fill=(255, 255, 255)))

    width = max(image.width for image in prepared)
    height = sum(image.height for image in prepared)
    canvas = Image.new("RGB", (width, height), (245, 245, 245))
    y = 0
    for image in prepared:
        canvas.paste(image, (0, y))
        y += image.height

    buffer = BytesIO()
    canvas.save(buffer, "JPEG", quality=88)
    return buffer.getvalue()

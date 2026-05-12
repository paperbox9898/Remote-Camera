import csv
import base64
import html
import io
import json
import os
import uuid
from datetime import datetime
from pathlib import Path
from typing import List, Optional

import cv2
import numpy as np
from fastapi import FastAPI, File, Form, Header, HTTPException, Query, UploadFile
from fastapi import WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, HTMLResponse
from PIL import Image

from human_guard.detection import create_detector, point_in_polygon

UPLOAD_DIR = Path("server_uploads")
RESULT_DIR = Path("server_results")
HISTORY_CSV = Path("server_history.csv")

UPLOAD_DIR.mkdir(exist_ok=True)
RESULT_DIR.mkdir(exist_ok=True)

API_KEY = os.getenv("HUMAN_GUARD_API_KEY", "")

detector = create_detector()

app = FastAPI(title="Human Guard Server")


def _check_api_key(value: Optional[str]) -> None:
    if API_KEY and value != API_KEY:
        raise HTTPException(status_code=401, detail="Invalid API Key")


def _parse_polygon(polygon: Optional[str], img_np: np.ndarray) -> Optional[List[List[float]]]:
    if not polygon:
        return None

    try:
        poly = json.loads(polygon)
        if not poly:
            return None
        if max(abs(float(point[0])) for point in poly) <= 1.0 and max(abs(float(point[1])) for point in poly) <= 1.0:
            height, width = img_np.shape[:2]
            return [[float(point[0]) * width, float(point[1]) * height] for point in poly]
        return [[float(point[0]), float(point[1])] for point in poly]
    except Exception:
        return None


def _point_in_rect(point: List[float], rect: tuple[int, int, int, int]) -> bool:
    x1, y1, x2, y2 = rect
    return x1 <= point[0] <= x2 and y1 <= point[1] <= y2


def _segments_intersect(
    a: tuple[float, float],
    b: tuple[float, float],
    c: tuple[float, float],
    d: tuple[float, float],
) -> bool:
    def orientation(p, q, r):
        value = (q[1] - p[1]) * (r[0] - q[0]) - (q[0] - p[0]) * (r[1] - q[1])
        if abs(value) < 1e-9:
            return 0
        return 1 if value > 0 else 2

    def on_segment(p, q, r):
        return (
            min(p[0], r[0]) <= q[0] <= max(p[0], r[0])
            and min(p[1], r[1]) <= q[1] <= max(p[1], r[1])
        )

    o1 = orientation(a, b, c)
    o2 = orientation(a, b, d)
    o3 = orientation(c, d, a)
    o4 = orientation(c, d, b)

    if o1 != o2 and o3 != o4:
        return True
    if o1 == 0 and on_segment(a, c, b):
        return True
    if o2 == 0 and on_segment(a, d, b):
        return True
    if o3 == 0 and on_segment(c, a, d):
        return True
    if o4 == 0 and on_segment(c, b, d):
        return True
    return False


def _box_intersects_polygon(box: tuple[int, int, int, int], poly: List[List[float]]) -> bool:
    x1, y1, x2, y2 = box
    box_corners = [(x1, y1), (x2, y1), (x2, y2), (x1, y2)]
    box_edges = list(zip(box_corners, box_corners[1:] + box_corners[:1]))
    polygon_edges = list(zip(poly, poly[1:] + poly[:1]))

    if any(point_in_polygon(corner, poly) for corner in box_corners):
        return True
    if any(_point_in_rect(point, box) for point in poly):
        return True
    for box_edge in box_edges:
        for poly_edge in polygon_edges:
            if _segments_intersect(box_edge[0], box_edge[1], tuple(poly_edge[0]), tuple(poly_edge[1])):
                return True
    return False


def _inspect_array(img_np: np.ndarray, confidence: float, polygon: Optional[str] = None) -> dict:
    confidence = max(confidence, 0.6)
    poly = _parse_polygon(polygon, img_np)

    detections = detector.detect(img_np, confidence_threshold=confidence)
    det_list = []
    alarm = False

    for det in detections:
        x1, y1, x2, y2 = det.box
        score = det.score
        foot_x = (x1 + x2) // 2
        foot_y = y2
        foot_point = [foot_x, foot_y]

        if poly:
            in_area = point_in_polygon(foot_point, poly)
            alarm = alarm or in_area
        else:
            in_area = True
            alarm = True

        det_list.append({
            "box": [x1, y1, x2, y2],
            "score": round(float(score), 4),
            "foot_point": foot_point,
            "in_area": in_area,
        })

    return {
        "status": "NG" if alarm else "OK",
        "alarm": alarm,
        "detector": detector.name,
        "detections": det_list,
    }


def _draw_result(img_np: np.ndarray, detections: List[dict], polygon: Optional[str] = None) -> np.ndarray:
    result_img = img_np.copy()

    for det in detections:
        x1, y1, x2, y2 = det["box"]
        score = det["score"]
        foot_x, foot_y = det["foot_point"]
        in_area = det["in_area"]

        # RGB colors: red for alarm, green for safe
        color = (255, 0, 0) if in_area else (0, 255, 0)
        cv2.rectangle(result_img, (x1, y1), (x2, y2), color, 2)
        cv2.circle(result_img, (foot_x, foot_y), 5, color, -1)
        label = f"{score:.2f}"
        font_scale = 1.1
        thickness = 3
        (label_w, label_h), baseline = cv2.getTextSize(
            label, cv2.FONT_HERSHEY_SIMPLEX, font_scale, thickness
        )
        label_x = x1 + 6
        label_y = min(max(y1 + label_h + 10, label_h + 10), max(label_h + 10, y2 - 8))
        cv2.rectangle(
            result_img,
            (x1, max(0, label_y - label_h - baseline - 8)),
            (
                min(result_img.shape[1] - 1, x1 + label_w + 14),
                min(result_img.shape[0] - 1, label_y + baseline + 4),
            ),
            color,
            -1,
        )
        cv2.putText(
            result_img,
            label,
            (label_x, label_y),
            cv2.FONT_HERSHEY_SIMPLEX,
            font_scale,
            (255, 255, 255),
            thickness,
        )

    if polygon:
        poly = _parse_polygon(polygon, img_np)
        if poly:
            pts = np.array(poly, dtype=np.int32)
            cv2.polylines(result_img, [pts], isClosed=True, color=(255, 165, 0), thickness=2)

    return result_img


def _read_history(limit: int = 100) -> List[dict]:
    if not HISTORY_CSV.exists():
        return []
    rows = []
    with open(HISTORY_CSV, "r", newline="", encoding="utf-8") as f:
        for row in csv.reader(f):
            if len(row) < 6 or row[0] == "time":
                continue
            rows.append(
                {
                    "time": row[0],
                    "uid": row[1],
                    "status": row[2],
                    "count": row[3],
                    "upload": row[4],
                    "result": row[5],
                }
            )
    return list(reversed(rows[-limit:]))


def _file_url(path: str, key: Optional[str]) -> str:
    suffix = f"?key={html.escape(key, quote=True)}" if key else ""
    return f"/files/{html.escape(path, quote=True)}{suffix}"


@app.get("/", response_class=HTMLResponse)
def dashboard(key: Optional[str] = Query(default=None)):
    _check_api_key(key)
    rows = _read_history()
    key_suffix = f"?key={html.escape(key, quote=True)}" if key else ""
    body_rows = []
    for row in rows:
        status = row["status"]
        status_class = "ng" if status == "NG" else "ok"
        upload_link = f'<a href="{_file_url(row["upload"], key)}" target="_blank">upload</a>'
        result_link = f'<a href="{_file_url(row["result"], key)}" target="_blank">result</a>'
        body_rows.append(
            "<tr>"
            f"<td>{html.escape(row['time'])}</td>"
            f"<td>{html.escape(row['uid'])}</td>"
            f"<td><span class='{status_class}'>{html.escape(status)}</span></td>"
            f"<td>{html.escape(row['count'])}</td>"
            f"<td>{upload_link}</td>"
            f"<td>{result_link}</td>"
            "</tr>"
        )
    rows_html = "\n".join(body_rows) or "<tr><td colspan='6'>No inspections yet.</td></tr>"
    return f"""<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta http-equiv="refresh" content="10">
  <title>Human Guard Dashboard</title>
  <style>
    body {{ font-family: Arial, sans-serif; margin: 24px; background: #f6f7f9; color: #1f2328; }}
    header {{ display: flex; justify-content: space-between; gap: 16px; align-items: center; margin-bottom: 16px; }}
    h1 {{ font-size: 24px; margin: 0; }}
    a {{ color: #0969da; }}
    table {{ width: 100%; border-collapse: collapse; background: white; }}
    th, td {{ padding: 10px; border-bottom: 1px solid #d8dee4; text-align: left; }}
    th {{ background: #eef1f4; }}
    .ok {{ color: #1a7f37; font-weight: 700; }}
    .ng {{ color: #cf222e; font-weight: 700; }}
    .hint {{ color: #57606a; font-size: 14px; }}
  </style>
</head>
<body>
  <header>
    <div>
      <h1>Human Guard Dashboard</h1>
      <div class="hint">Auto-refresh every 10 seconds. Latest inspections first.</div>
    </div>
    <div><a href="/health{key_suffix}">health</a></div>
  </header>
  <table>
    <thead>
      <tr>
        <th>Time</th>
        <th>UID</th>
        <th>Status</th>
        <th>Detections</th>
        <th>Upload</th>
        <th>Result</th>
      </tr>
    </thead>
    <tbody>{rows_html}</tbody>
  </table>
</body>
</html>"""


@app.get("/files/{file_path:path}")
def serve_file(file_path: str, key: Optional[str] = Query(default=None)):
    _check_api_key(key)
    target = Path(file_path).resolve()
    allowed_roots = [UPLOAD_DIR.resolve(), RESULT_DIR.resolve()]
    if not any(target.is_relative_to(root) for root in allowed_roots):
        raise HTTPException(status_code=404, detail="File not found")
    if not target.exists() or not target.is_file():
        raise HTTPException(status_code=404, detail="File not found")
    return FileResponse(target)


@app.get("/health")
def health(x_api_key: Optional[str] = Header(default=None), key: Optional[str] = Query(default=None)):
    _check_api_key(x_api_key or key)
    return {"ok": True, "detector": detector.name}


@app.websocket("/stream")
async def stream(
    websocket: WebSocket,
    key: Optional[str] = Query(default=None),
    confidence: float = Query(default=0.6),
    polygon: Optional[str] = Query(default=None),
    overlay: bool = Query(default=False),
):
    if API_KEY and key != API_KEY:
        await websocket.close(code=1008)
        return

    await websocket.accept()
    try:
        while True:
            img_bytes = await websocket.receive_bytes()
            pil_img = Image.open(io.BytesIO(img_bytes)).convert("RGB")
            img_np = np.array(pil_img)
            result = _inspect_array(img_np, confidence=confidence, polygon=polygon)
            result["frame_width"] = int(img_np.shape[1])
            result["frame_height"] = int(img_np.shape[0])

            if overlay:
                result_img = _draw_result(img_np, result["detections"], polygon=polygon)
                buffer = io.BytesIO()
                Image.fromarray(result_img).save(buffer, "JPEG", quality=75)
                result["overlay_jpeg_base64"] = base64.b64encode(buffer.getvalue()).decode("ascii")

            await websocket.send_text(json.dumps(result, ensure_ascii=False))
    except WebSocketDisconnect:
        return
    except Exception as exc:
        await websocket.send_text(json.dumps({"error": str(exc)}, ensure_ascii=False))
        await websocket.close(code=1011)


@app.post("/inspect")
async def inspect(
    image: UploadFile = File(...),
    confidence: float = Form(default=0.6),
    polygon: Optional[str] = Form(default=None),
    x_api_key: Optional[str] = Header(default=None),
):
    _check_api_key(x_api_key)

    img_bytes = await image.read()
    pil_img = Image.open(io.BytesIO(img_bytes)).convert("RGB")
    img_np = np.array(pil_img)

    uid = uuid.uuid4().hex[:12]
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")

    upload_path = UPLOAD_DIR / f"upload_{ts}_{uid}.jpg"
    pil_img.save(str(upload_path), "JPEG")

    result = _inspect_array(img_np, confidence=confidence, polygon=polygon)
    det_list = result["detections"]
    result_img = _draw_result(img_np, det_list, polygon=polygon)

    result_path = RESULT_DIR / f"result_{ts}_{uid}.jpg"
    Image.fromarray(result_img).save(str(result_path), "JPEG")

    status = result["status"]

    with open(HISTORY_CSV, "a", newline="", encoding="utf-8") as f:
        csv.writer(f).writerow([ts, uid, status, len(det_list), str(upload_path), str(result_path)])

    result["result_image"] = str(result_path)
    return result


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("home_server.server:app", host="0.0.0.0", port=8000, reload=False)

import csv
import io
import json
import os
import uuid
from datetime import datetime
from pathlib import Path
from typing import Optional

import cv2
import numpy as np
from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile
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


def _check_api_key(x_api_key: Optional[str]) -> None:
    if API_KEY and x_api_key != API_KEY:
        raise HTTPException(status_code=401, detail="Invalid API Key")


@app.get("/health")
def health(x_api_key: Optional[str] = Header(default=None)):
    _check_api_key(x_api_key)
    return {"ok": True, "detector": detector.name}


@app.post("/inspect")
async def inspect(
    image: UploadFile = File(...),
    confidence: float = Form(default=0.35),
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

    poly = None
    if polygon:
        try:
            poly = json.loads(polygon)
        except Exception:
            poly = None

    detections = detector.detect(img_np, confidence_threshold=confidence)

    result_img = img_np.copy()
    det_list = []
    alarm = False

    for det in detections:
        x1, y1, x2, y2 = det.box
        score = det.score
        foot_x = (x1 + x2) // 2
        foot_y = y2
        foot_point = [foot_x, foot_y]

        in_area = False
        if poly:
            in_area = point_in_polygon(foot_point, poly)
            if in_area:
                alarm = True
        else:
            alarm = True
            in_area = True

        # RGB colors: red for alarm, green for safe
        color = (255, 0, 0) if in_area else (0, 255, 0)
        cv2.rectangle(result_img, (x1, y1), (x2, y2), color, 2)
        cv2.circle(result_img, (foot_x, foot_y), 5, color, -1)
        cv2.putText(
            result_img, f"{score:.2f}", (x1, max(y1 - 5, 0)),
            cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 1,
        )

        det_list.append({
            "box": [x1, y1, x2, y2],
            "score": round(float(score), 4),
            "foot_point": foot_point,
            "in_area": in_area,
        })

    if poly:
        pts = np.array(poly, dtype=np.int32)
        cv2.polylines(result_img, [pts], isClosed=True, color=(255, 165, 0), thickness=2)

    result_path = RESULT_DIR / f"result_{ts}_{uid}.jpg"
    Image.fromarray(result_img).save(str(result_path), "JPEG")

    status = "NG" if alarm else "OK"

    with open(HISTORY_CSV, "a", newline="", encoding="utf-8") as f:
        csv.writer(f).writerow([ts, uid, status, len(det_list), str(upload_path), str(result_path)])

    return {
        "status": status,
        "alarm": alarm,
        "detector": detector.name,
        "detections": det_list,
        "result_image": str(result_path),
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("home_server.server:app", host="0.0.0.0", port=8000, reload=False)

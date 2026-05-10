from __future__ import annotations

import dataclasses
from pathlib import Path
from typing import List, Optional, Sequence, Tuple

import cv2
import numpy as np

DEFAULT_MODEL_PATH = Path("yolo_model/yolov8n_float32.tflite")


@dataclasses.dataclass
class Detection:
    box: Tuple[int, int, int, int]  # x1, y1, x2, y2
    score: float


def point_in_polygon(point: Sequence[float], polygon: Sequence[Sequence[float]]) -> bool:
    """Ray-casting algorithm for point-in-polygon test."""
    x, y = float(point[0]), float(point[1])
    n = len(polygon)
    inside = False
    j = n - 1
    for i in range(n):
        xi, yi = float(polygon[i][0]), float(polygon[i][1])
        xj, yj = float(polygon[j][0]), float(polygon[j][1])
        if ((yi > y) != (yj > y)) and (
            x < (xj - xi) * (y - yi) / (yj - yi + 1e-12) + xi
        ):
            inside = not inside
        j = i
    return inside


class YoloTfliteDetector:
    name = "yolov8n-tflite"

    def __init__(self, model_path: Path) -> None:
        try:
            import tflite_runtime.interpreter as tflite
        except ImportError:
            import tensorflow.lite as tflite  # type: ignore

        self._interp = tflite.Interpreter(model_path=str(model_path))
        self._interp.allocate_tensors()
        inp = self._interp.get_input_details()[0]
        self._input_idx: int = inp["index"]
        self._input_shape = inp["shape"]  # [1, H, W, 3]
        self._output_details = self._interp.get_output_details()

    def detect(self, image_rgb: np.ndarray, confidence_threshold: float = 0.35) -> List[Detection]:
        h_in = int(self._input_shape[1])
        w_in = int(self._input_shape[2])
        orig_h, orig_w = image_rgb.shape[:2]

        img = cv2.resize(image_rgb, (w_in, h_in)).astype(np.float32) / 255.0
        img = np.expand_dims(img, axis=0)

        self._interp.set_tensor(self._input_idx, img)
        self._interp.invoke()

        output = self._interp.get_tensor(self._output_details[0]["index"])
        # YOLOv8 exports [1, 84, num_boxes]; transpose to [1, num_boxes, 84]
        if output.ndim == 3 and output.shape[1] < output.shape[2]:
            output = output.transpose(0, 2, 1)

        detections: List[Detection] = []
        for row in output[0]:
            cx, cy, bw, bh = float(row[0]), float(row[1]), float(row[2]), float(row[3])
            # COCO class 0 = person
            person_score = float(row[4]) if len(row) > 4 else 0.0
            if person_score < confidence_threshold:
                continue

            x1 = int((cx - bw / 2) * orig_w)
            y1 = int((cy - bh / 2) * orig_h)
            x2 = int((cx + bw / 2) * orig_w)
            y2 = int((cy + bh / 2) * orig_h)
            x1, y1 = max(0, x1), max(0, y1)
            x2, y2 = min(orig_w - 1, x2), min(orig_h - 1, y2)
            detections.append(Detection(box=(x1, y1, x2, y2), score=person_score))

        if len(detections) > 1:
            boxes_xywh = [
                [d.box[0], d.box[1], d.box[2] - d.box[0], d.box[3] - d.box[1]]
                for d in detections
            ]
            scores = [d.score for d in detections]
            indices = cv2.dnn.NMSBoxes(boxes_xywh, scores, confidence_threshold, 0.45)
            if len(indices) > 0:
                flat = np.array(indices).flatten()
                detections = [detections[i] for i in flat]

        return detections


class HogFallbackDetector:
    name = "hog-fallback"

    def __init__(self) -> None:
        self._hog = cv2.HOGDescriptor()
        self._hog.setSVMDetector(cv2.HOGDescriptor_getDefaultPeopleDetector())

    def detect(self, image_rgb: np.ndarray, confidence_threshold: float = 0.35) -> List[Detection]:
        bgr = cv2.cvtColor(image_rgb, cv2.COLOR_RGB2BGR)
        rects, weights = self._hog.detectMultiScale(
            bgr, winStride=(8, 8), padding=(4, 4), scale=1.05
        )
        detections: List[Detection] = []
        if len(rects) == 0:
            return detections
        for (x, y, w, h), weight in zip(rects, weights):
            score = float(np.squeeze(weight))
            if score < confidence_threshold:
                continue
            detections.append(
                Detection(box=(int(x), int(y), int(x + w), int(y + h)), score=score)
            )
        return detections


def create_detector(
    model_path: Optional[Path] = None,
) -> "YoloTfliteDetector | HogFallbackDetector":
    path = model_path or DEFAULT_MODEL_PATH
    try:
        return YoloTfliteDetector(path)
    except Exception as exc:
        print(f"[HumanGuard] TFLite load failed ({exc}), using HOG fallback.")
        return HogFallbackDetector()

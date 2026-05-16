# YOLO Model Files

Place local detection model files here.

Supported examples:

- `yolo11s.pt`
- `yolo11n.pt`
- `yolov8n_float32.tflite`

Model weights are local runtime assets and should not be committed unless explicitly requested.

If no YOLO/TFLite model can be loaded, the server falls back to OpenCV HOG detection.

# YOLOv8n TFLite Model

이 디렉터리에 `yolov8n_float32.tflite` 파일을 배치하세요.

## 모델 생성 방법

```bash
pip install ultralytics
python -c "from ultralytics import YOLO; YOLO('yolov8n.pt').export(format='tflite')"
# 생성된 yolov8n_float32.tflite 를 이 디렉터리로 복사
```

모델 파일이 없으면 서버는 자동으로 **OpenCV HOG Fallback** 모드로 실행됩니다.

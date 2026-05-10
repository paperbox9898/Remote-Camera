# Remote Camera — 집컴 서버 운영 가이드

이 저장소는 Android 앱에서 사진을 촬영해 집컴 Python 서버로 전송하고,
인체 감지 모델이 OK/NG를 판정하는 시스템입니다.

---

## 1. 프로젝트 구조

```
Remote-Camera/
├── home_server/
│   └── server.py          # FastAPI 서버 (메인 진입점)
├── human_guard/
│   └── detection.py       # 인체 감지 로직 (YOLO / HOG fallback)
├── yolo_model/
│   └── (여기에 yolov8n_float32.tflite 배치)
├── requirements.txt       # Python 의존성
├── mobile-app/            # Android 앱 소스 (참고용)
└── .github/workflows/
    └── android-apk.yml    # APK 자동 빌드
```

---

## 2. 처음 설치 (집컴에서 한 번만)

```bash
git clone https://github.com/paperbox9898/Remote-Camera
cd Remote-Camera
pip install -r requirements.txt
```

### YOLO 모델 준비 (선택 — 없으면 HOG fallback 자동 사용)

```bash
pip install ultralytics
python -c "from ultralytics import YOLO; YOLO('yolov8n.pt').export(format='tflite')"
mkdir -p yolo_model
mv yolov8n_float32.tflite yolo_model/
```

---

## 3. 서버 실행 (매번)

터미널 A — 서버:
```bash
cd Remote-Camera
export HUMAN_GUARD_API_KEY="여기에_긴_비밀번호"
python -m home_server.server
# → http://localhost:8000 에서 실행됨
```

터미널 B — 외부 접속용 Cloudflare Tunnel:
```bash
cloudflared tunnel --url http://localhost:8000
# → https://xxxx-xxxx.trycloudflare.com 주소가 출력됨
# 이 주소를 앱에 입력
```

---

## 4. 환경변수

| 변수 | 설명 | 기본값 |
|------|------|--------|
| `HUMAN_GUARD_API_KEY` | API 인증 키 (비워두면 인증 없음) | 없음 |

---

## 5. API 명세

### GET /health

서버 상태 확인.

```http
GET /health
X-API-Key: <your-key>
```

응답:
```json
{"ok": true, "detector": "yolov8n-tflite"}
```

---

### POST /inspect

이미지를 업로드해 인체 감지 판정.

```http
POST /inspect
X-API-Key: <your-key>
Content-Type: multipart/form-data
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `image` | file | ✅ | 촬영 이미지 (JPEG/PNG) |
| `confidence` | float | ❌ | 감지 임계값 (기본: 0.35) |
| `polygon` | string | ❌ | 감시 영역 JSON `[[x,y],[x,y],...]` |

응답:
```json
{
  "status": "OK",
  "alarm": false,
  "detector": "yolov8n-tflite",
  "detections": [
    {
      "box": [120, 80, 340, 480],
      "score": 0.87,
      "foot_point": [230, 480],
      "in_area": false
    }
  ],
  "result_image": "server_results/result_20240101_120000_abc123.jpg"
}
```

- `alarm: true` + `status: "NG"` → 사람 감지됨 (앱이 빨간색 표시)
- `alarm: false` + `status: "OK"` → 이상 없음 (앱이 초록색 표시)
- `polygon` 미지정 시 사람이 하나라도 있으면 NG
- `polygon` 지정 시 발 위치가 polygon 안에 있을 때만 NG

---

## 6. 생성되는 파일

| 경로 | 내용 |
|------|------|
| `server_uploads/` | 업로드된 원본 이미지 |
| `server_results/` | 감지 박스 그린 결과 이미지 |
| `server_history.csv` | 판정 이력 (시각, UID, 상태, 감지수) |

---

## 7. 감지 로직 (`human_guard/detection.py`)

- **`create_detector()`** — TFLite 로드 성공 시 `YoloTfliteDetector`, 실패 시 `HogFallbackDetector` 반환
- **`YoloTfliteDetector`** — YOLOv8n TFLite 모델, COCO class 0(person) 감지
- **`HogFallbackDetector`** — OpenCV HOG 기반, 모델 없이 동작
- **`point_in_polygon(point, polygon)`** — Ray-casting 알고리즘으로 발 위치가 영역 안인지 판정

---

## 8. 자주 하는 작업

### 서버 헬스체크
```bash
curl http://localhost:8000/health
```

### 로컬에서 이미지 테스트
```bash
curl -X POST http://localhost:8000/inspect \
  -F "image=@test.jpg" \
  -F "confidence=0.35"
```

### API Key 있을 때
```bash
curl -X POST http://localhost:8000/inspect \
  -H "X-API-Key: 여기에_비밀번호" \
  -F "image=@test.jpg"
```

### polygon 영역 지정 테스트
```bash
curl -X POST http://localhost:8000/inspect \
  -H "X-API-Key: 여기에_비밀번호" \
  -F "image=@test.jpg" \
  -F 'polygon=[[100,200],[500,200],[500,600],[100,600]]'
```

### 판정 이력 확인
```bash
cat server_history.csv
```

---

## 9. 앱 연결

앱 실행 후:
1. **서버 URL**: Cloudflare Tunnel 주소 입력 (`https://xxxx.trycloudflare.com`)
2. **API Key**: `HUMAN_GUARD_API_KEY` 값 입력
3. **사진 촬영 후 판정** 버튼 클릭

같은 Wi-Fi 내에서 테스트할 경우 `http://192.168.x.x:8000` 직접 입력도 가능합니다.
(`HUMAN_GUARD_API_KEY` 미설정 시 API Key 칸 비워둬도 됩니다)

---

## 10. APK 받는 법

- GitHub → Actions → **Android APK Build** → 최근 빌드 → **Artifacts** → `app-debug` 다운로드
- 정식 Release: `git tag v0.x.x && git push origin v0.x.x`

---

## 11. 의존성 (`requirements.txt`)

```
fastapi
uvicorn[standard]
python-multipart
opencv-python
numpy
Pillow
tflite-runtime
```

`tflite-runtime` 설치가 안 되는 환경(Windows 등):
```bash
pip install tensorflow
```
서버 코드가 자동으로 tensorflow.lite로 fallback합니다.

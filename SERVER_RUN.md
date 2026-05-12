# 서버 실행 가이드

집컴에서 Human Guard 서버를 켜고 Android 앱이 접속하게 만드는 절차입니다.

## 1. 저장소로 이동

```bash
cd "/mnt/c/Users/dlack/code/02 Human Detect Model"
```

Windows PowerShell에서 작업 중이면 프로젝트 폴더로 이동합니다.

```powershell
cd "C:\Users\dlack\code\02 Human Detect Model"
```

## 2. 가상환경 활성화

이미 `venv`가 있으면 활성화합니다.

```bash
source venv/bin/activate
```

PowerShell에서는 아래 명령을 사용합니다.

```powershell
.\venv\Scripts\Activate.ps1
```

가상환경이 없거나 의존성이 부족하면 한 번만 설치합니다.

```bash
python -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

Windows에서 `tflite-runtime` 설치가 실패하면 TensorFlow를 대신 설치합니다.

```bash
pip install tensorflow
```

## 3. API Key 설정

운영할 때는 긴 비밀번호를 API Key로 설정합니다.

```bash
export HUMAN_GUARD_API_KEY="여기에_긴_비밀번호"
```

PowerShell에서는 아래처럼 설정합니다.

```powershell
$env:HUMAN_GUARD_API_KEY="여기에_긴_비밀번호"
```

로컬 테스트만 할 때는 API Key를 비워도 되지만, 외부에서 접속하게 할 때는 반드시 설정합니다.

## 4. 서버 실행

```bash
python -m home_server.server
```

정상 실행되면 서버는 아래 주소에서 대기합니다.

```text
http://localhost:8000
```

서버를 끄려면 실행 중인 터미널에서 `Ctrl+C`를 누릅니다.

## 5. 서버 상태 확인

API Key를 설정하지 않은 경우:

```bash
curl http://localhost:8000/health
```

API Key를 설정한 경우:

```bash
curl -H "X-API-Key: 여기에_긴_비밀번호" http://localhost:8000/health
```

정상 응답 예시:

```json
{"ok":true,"detector":"yolo11s-ultralytics-cuda"}
```

`detector` 값은 실행 환경에 따라 달라질 수 있습니다.

| detector 예시 | 의미 |
|---|---|
| `yolo11s-ultralytics-cuda` | `yolo_model/yolo11s.pt`를 GPU로 실행 |
| `yolo11s-ultralytics-cpu` | `yolo_model/yolo11s.pt`를 CPU로 실행 |
| `yolov8n-tflite` | `yolo_model/yolov8n_float32.tflite` 사용 |
| `hog-fallback` | YOLO 모델 로드 실패 후 OpenCV HOG fallback 사용 |

## 6. 대시보드 확인

브라우저에서 아래 주소를 엽니다.

```text
http://localhost:8000
```

API Key가 설정되어 있으면 URL에 `key`를 붙입니다.

```text
http://localhost:8000?key=여기에_긴_비밀번호
```

대시보드에서는 최근 판정 이력, 원본 이미지, 결과 이미지를 확인할 수 있습니다.

## 7. 이미지 업로드 테스트

테스트 이미지가 `test.jpg`라는 이름으로 있을 때:

```bash
curl -X POST http://localhost:8000/inspect \
  -H "X-API-Key: 여기에_긴_비밀번호" \
  -F "image=@test.jpg" \
  -F "confidence=0.6"
```

감시 영역 polygon을 같이 보낼 때:

```bash
curl -X POST http://localhost:8000/inspect \
  -H "X-API-Key: 여기에_긴_비밀번호" \
  -F "image=@test.jpg" \
  -F "confidence=0.6" \
  -F 'polygon=[[100,200],[500,200],[500,600],[100,600]]'
```

## 8. 휴대폰에서 접속하기

같은 Wi-Fi에서 테스트할 경우 집컴의 내부 IP를 앱에 입력합니다.

```text
http://192.168.x.x:8000
```

외부에서 접속하려면 Tailscale 또는 Cloudflare Tunnel을 사용합니다.

### Tailscale 사용

서버를 먼저 켠 뒤 Tailscale Serve를 연결합니다.

```powershell
tailscale serve --bg 8000
```

앱의 서버 URL에는 Tailscale에서 사용하는 HTTPS 주소를 입력합니다.

```text
https://chamin.taile54870.ts.net
```

### Cloudflare Tunnel 사용

서버를 먼저 켠 뒤 다른 터미널에서 실행합니다.

```bash
cloudflared tunnel --url http://localhost:8000
```

출력되는 `https://xxxx.trycloudflare.com` 주소를 앱의 서버 URL에 입력합니다.

## 9. 생성 파일 위치

| 경로 | 내용 |
|---|---|
| `server_uploads/` | 업로드된 원본 이미지 |
| `server_results/` | 감지 박스가 그려진 결과 이미지 |
| `server_history.csv` | 판정 이력 |

## 10. 자주 막히는 경우

### `401 Invalid API Key`

서버 실행 터미널의 `HUMAN_GUARD_API_KEY` 값과 앱 또는 `curl`의 API Key가 같은지 확인합니다.

### 앱에서 접속 실패

서버 터미널이 켜져 있는지 확인하고, 집컴 방화벽에서 `8000` 포트가 막혀 있지 않은지 확인합니다. 외부 접속이면 Tailscale 또는 Cloudflare Tunnel 주소가 최신인지도 확인합니다.

### 모델 로드 실패

`/health`의 `detector`가 `hog-fallback`이면 YOLO 모델 로드에 실패한 상태입니다. `yolo_model/yolo11s.pt` 또는 `yolo_model/yolov8n_float32.tflite` 파일이 있는지 확인합니다.

### 포트가 이미 사용 중

다른 서버가 `8000` 포트를 쓰고 있을 수 있습니다. 기존 서버를 종료한 뒤 다시 실행합니다.

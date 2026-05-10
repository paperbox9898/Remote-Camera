# Remote Camera - Human Detection MVP

Android 앱에서 사진을 촬영해 집컴 Python 서버로 전송하고, 인체 감지 모델이 OK/NG를 판정하는 시스템입니다.

## 구조

```
remote-camera/
├── home_server/          # 집컴 FastAPI 서버
├── human_guard/          # 인체 감지 공통 로직
├── mobile-app/           # Android 앱
├── yolo_model/           # TFLite 모델 배치 위치
└── .github/workflows/    # APK 자동 빌드
```

---

## 1. 집컴 서버 실행 방법

### 의존성 설치

```bash
pip install -r requirements.txt
```

### (선택) YOLO 모델 배치

```bash
mkdir -p yolo_model
# yolov8n_float32.tflite 를 yolo_model/ 아래에 복사
```

모델이 없으면 OpenCV HOG Fallback으로 자동 전환됩니다.

### 서버 실행

```bash
export HUMAN_GUARD_API_KEY="사용자가_정한_긴_비밀번호"
python -m home_server.server
```

서버가 `http://localhost:8000`에서 실행됩니다.

---

## 2. Cloudflare Tunnel 실행 방법

외부(모바일 데이터 등)에서도 접속할 수 있도록 Cloudflare Tunnel을 사용합니다.
포트포워딩 없이 HTTPS 주소를 자동 발급받습니다.

### cloudflared 설치

```bash
# Linux (x86_64)
curl -L https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 -o cloudflared
chmod +x cloudflared && sudo mv cloudflared /usr/local/bin/

# macOS
brew install cloudflared

# Windows
winget install Cloudflare.cloudflared
```

### 터널 실행

서버가 실행 중인 상태에서 별도 터미널로 실행합니다.

```bash
cloudflared tunnel --url http://localhost:8000
```

출력 예시:

```
Your quick Tunnel has been created! Visit it at:
 https://xxxx-xxxx-xxxx.trycloudflare.com
```

이 주소를 앱의 서버 URL 칸에 입력합니다.

---

## 3. 앱에 서버 URL / API Key 입력하는 방법

1. APK를 설치하고 앱을 실행합니다.
2. **서버 URL** 칸에 Cloudflare Tunnel 주소를 입력합니다.
   - 예: `https://xxxx-xxxx-xxxx.trycloudflare.com`
3. **API Key** 칸에 `HUMAN_GUARD_API_KEY`로 설정한 비밀번호를 입력합니다.
   - 로컬 테스트(API Key 미설정)인 경우 비워둡니다.
4. **사진 촬영 후 판정** 버튼을 누릅니다.

---

## 4. GitHub Actions로 APK 받는 방법

### 수동 빌드

1. GitHub 저장소 > **Actions** 탭을 클릭합니다.
2. **Android APK Build** 워크플로를 선택합니다.
3. **Run workflow** 버튼을 클릭합니다.
4. 빌드 완료 후 **Artifacts** 섹션에서 `app-debug` 를 다운로드합니다.
5. 압축 해제 후 `app-debug.apk`를 기기에 설치합니다.

---

## 5. v0.1.0 태그로 Release 배포하는 방법

```bash
git tag v0.1.0
git push origin v0.1.0
```

태그가 push되면 GitHub Actions가 자동으로:
1. APK를 빌드합니다.
2. GitHub Release를 생성합니다.
3. `app-debug.apk`를 Release에 첨부합니다.

Release는 저장소 > **Releases** 탭에서 확인할 수 있습니다.

---

## API 명세

### GET /health

```http
GET /health
X-API-Key: <your-key>
```

```json
{"ok": true, "detector": "yolov8n-tflite"}
```

### POST /inspect

```http
POST /inspect
X-API-Key: <your-key>
Content-Type: multipart/form-data
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `image` | file | 촬영 이미지 |
| `confidence` | float | 감지 임계값 (기본 0.35) |
| `polygon` | string | 감시 영역 JSON (선택) |

응답 예시:

```json
{
  "status": "NG",
  "alarm": true,
  "detector": "yolov8n-tflite",
  "detections": [
    {
      "box": [120, 80, 340, 480],
      "score": 0.87,
      "foot_point": [230, 480],
      "in_area": true
    }
  ],
  "result_image": "server_results/result_20240101_120000_abc123.jpg"
}
```

---

## 보안 메모

- `HUMAN_GUARD_API_KEY`를 설정하지 않으면 인증 없이 허용됩니다 (로컬 테스트 전용).
- 운영 환경에서는 반드시 강력한 임의 문자열을 API Key로 설정하세요.
- Cloudflare Tunnel은 자동으로 HTTPS를 제공합니다.

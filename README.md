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

## 2. Tailscale 고정 연결

임시 터널 주소가 계속 바뀌지 않도록 Tailscale 사용을 권장합니다.

### 설치 및 로그인

1. 집컴에 Tailscale 설치 후 로그인
2. 휴대폰에 Tailscale 설치 후 같은 계정으로 로그인
3. 집컴 서버를 실행

```bash
export HUMAN_GUARD_API_KEY="사용자가_정한_긴_비밀번호"
python -m home_server.server
```

앱 기본 서버 URL은 아래 주소입니다.

```text
https://chamin.taile54870.ts.net
```

휴대폰에서 Tailscale을 켠 상태로 앱을 실행하면 이 주소로 접속합니다.

### Tailscale Serve 사용

Windows/WSL 포트 경계 때문에 직접 접속이 막히면 Tailscale Serve를 활성화한 뒤 실행합니다.

```powershell
tailscale serve --bg 8000
```

Serve가 활성화되지 않았다면 Tailscale이 출력하는 승인 링크를 브라우저에서 열어 승인합니다.

---

## 2-1. 임시 터널 대안

Tailscale을 쓸 수 없는 경우 Cloudflare Tunnel 또는 localhost.run을 임시로 사용할 수 있습니다. 단, 무료 임시 터널은 주소가 바뀔 수 있습니다.

---

## 3. 앱에 서버 URL / API Key 입력하는 방법

1. APK를 설치하고 앱을 실행합니다.
2. **서버 URL** 칸을 확인합니다.
   - Tailscale 기본값: `https://chamin.taile54870.ts.net`
   - 임시 터널 사용 시: `https://xxxx.example.com`
3. **API Key** 칸에 `HUMAN_GUARD_API_KEY`로 설정한 비밀번호를 입력합니다.
   - 로컬 테스트(API Key 미설정)인 경우 비워둡니다.
4. **사진 촬영 후 판정** 버튼을 누릅니다.

앱은 서버 URL과 API Key를 기기에 저장하므로 다음 실행 때 다시 입력하지 않아도 됩니다.

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

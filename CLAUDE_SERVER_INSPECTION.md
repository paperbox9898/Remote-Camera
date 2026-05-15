# Server-side Claude inspection

서버가 YOLO/HOG 감지에서 `alarm: true`를 판단하면 사람 영역을 크롭하고, 설정된 간격마다 Claude API로 정밀 검사를 요청할 수 있습니다.

## Environment variables

| Variable | Description | Default |
| --- | --- | --- |
| `ANTHROPIC_API_KEY` 또는 `CLAUDE_API_KEY` | Claude API 호출 키. 없으면 정밀 검사는 비활성화됩니다. | 없음 |
| `CLAUDE_MODEL` | Claude 모델명 | `claude-3-5-sonnet-latest` |
| `CLAUDE_INSPECTION_INTERVAL_SECONDS` | Claude 재호출 최소 간격 | `50` |

PowerShell 예시:

```powershell
$env:HUMAN_GUARD_API_KEY="server-password"
$env:ANTHROPIC_API_KEY="sk-ant-..."
$env:CLAUDE_INSPECTION_INTERVAL_SECONDS="50"
python -m home_server.server
```

## Reference documents

품질 검사 기준서, 안전 작업 표준서, 불량 판정 기준 등을 업로드하면 Claude 프롬프트에 참고 문서로 포함됩니다. `Anomaly-site`처럼 정상/불량 참조 이미지를 함께 올려 두면 Claude 요청에서 참조 이미지가 먼저 전달되고, 마지막 이미지가 실제 검사 대상 크롭 이미지로 전달됩니다.

앱에서는 서버 URL과 API Key를 입력한 뒤 **검사 기준/참조 이미지 업로드** 버튼을 눌러 문서나 이미지를 선택하면 됩니다. 선택한 파일은 서버의 `/references`로 업로드되고, 이후 YOLO가 사람을 감지해 Claude 정밀 검사가 실행될 때 자동으로 참고 자료로 사용됩니다.

```bash
curl -X POST http://localhost:8000/references \
  -H "X-API-Key: your-server-key" \
  -F "document=@inspection_standard.md"
```

참조 이미지 예시:

```bash
curl -X POST http://localhost:8000/references \
  -H "X-API-Key: your-server-key" \
  -F "document=@normal_sample.jpg"
```

조회:

```bash
curl -H "X-API-Key: your-server-key" http://localhost:8000/references
```

현재 텍스트 추출은 `txt`, `md`, `csv`, `json`, `docx`에 적합합니다. 이미지 참조는 `jpg`, `jpeg`, `png`, `webp`, `gif`를 지원합니다. PDF는 저장은 가능하지만 텍스트 추출 라이브러리가 없으면 기준 문서 내용으로 충분히 반영되지 않을 수 있습니다.

## Inspection response

`/inspect` 또는 `/stream`에서 사람 감지 알람이 발생하면 응답에 다음 필드가 추가될 수 있습니다.

```json
{
  "crop_images": ["server_crops/crop_20260515_120000_abcd_0.jpg"],
  "claude_inspection": {
    "status": "WATCH",
    "severity": "MEDIUM",
    "summary": "작업자가 제한 구역 안에 있는 것으로 보입니다.",
    "evidence": ["사람 하체가 지정 영역 내부에 있음"],
    "reference_matches": ["제한 구역 출입 금지"],
    "recommendations": ["현장 확인 후 작업자 이동 조치"],
    "false_positive_likely": false
  }
}
```

쿨다운 중이면 `claude_inspection.skipped=true`와 남은 시간이 반환될 수 있습니다. 단건 테스트에서 쿨다운을 무시하려면 `/inspect` multipart form에 `claude_force=true`를 추가합니다.

생성 파일:

| Path | Description |
| --- | --- |
| `server_crops/` | Claude로 보낸 사람 크롭 이미지 |
| `inspection_references/` | 업로드한 검사 기준 문서 |
| `claude_history.csv` | Claude 검사 이력 |

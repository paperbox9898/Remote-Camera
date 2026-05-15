package com.remotecamera.app

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class ClaudeInspectionContext(
    val source: String,
    val yoloStatus: String,
    val detectionCount: Int,
    val areaConfigured: Boolean,
)

data class ClaudeInspectionResult(
    val status: String,
    val severity: String,
    val summary: String,
    val evidence: List<String>,
    val recommendations: List<String>,
    val falsePositiveLikely: Boolean,
)

class ClaudeInspectionException(message: String) : Exception(message)

class ClaudeInspector(
    private val client: OkHttpClient,
    private val model: String = BuildConfig.CLAUDE_MODEL,
) {
    suspend fun inspect(
        jpegBytes: ByteArray,
        apiKey: String,
        context: ClaudeInspectionContext,
    ): ClaudeInspectionResult = withContext(Dispatchers.IO) {
        val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val content = JSONArray()
            .put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", "image/jpeg")
                            .put("data", base64Image),
                    ),
            )
            .put(
                JSONObject()
                    .put("type", "text")
                    .put("text", buildSafetyPrompt(context)),
            )

        val payload = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", content),
                ),
            )

        val request = Request.Builder()
            .url(CLAUDE_API_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ClaudeInspectionException(parseErrorMessage(response.code, body))
            }

            val rawText = extractResponseText(JSONObject(body))
            if (rawText.isBlank()) {
                throw ClaudeInspectionException("Claude 응답이 비어 있습니다.")
            }

            val parsed = try {
                JSONObject(stripJsonFences(rawText))
            } catch (e: Exception) {
                throw ClaudeInspectionException("Claude JSON 파싱 실패: ${rawText.take(120)}")
            }

            ClaudeInspectionResult(
                status = parsed.optString("status", "WATCH"),
                severity = parsed.optString("severity", "MEDIUM"),
                summary = parsed.optString("summary", "요약 없음"),
                evidence = parsed.optStringArray("evidence"),
                recommendations = parsed.optStringArray("recommendations"),
                falsePositiveLikely = parsed.optBoolean("falsePositiveLikely", false),
            )
        }
    }

    private fun buildSafetyPrompt(context: ClaudeInspectionContext): String {
        val areaText = if (context.areaConfigured) {
            "앱에서 감시 다각형 영역이 설정되어 있으며, YOLO 판정은 해당 영역 기준입니다."
        } else {
            "별도 감시 영역이 없으므로 프레임 내 사람 감지를 기준으로 합니다."
        }

        return """당신은 제조 현장 안전 감시 보조자입니다.

YOLO 인체 감지 결과:
- 출처: ${context.source}
- YOLO 상태: ${context.yoloStatus}
- 감지 인원: ${context.detectionCount}명
- 영역 기준: $areaText

이미지를 보고 사람이 실제로 있는지, 위험 구역 침입 또는 위험 행동으로 볼 수 있는지, 오탐 가능성이 높은지 판단하세요.
설비, 리프터, 바닥, 차체 하부, 작업자 자세, 가려짐, 조명 문제를 함께 확인하세요.

반드시 다음 JSON 형식으로만 한국어로 응답하세요. 다른 텍스트나 마크다운은 포함하지 마세요:
{
  "status": "DANGER",
  "severity": "HIGH",
  "summary": "한 문장 요약",
  "evidence": ["이미지에서 확인한 근거"],
  "recommendations": ["즉시 필요한 조치"],
  "falsePositiveLikely": false
}

status 값: SAFE, WATCH, DANGER
severity 값: NONE, LOW, MEDIUM, HIGH, CRITICAL
판단이 불확실하면 WATCH를 사용하고, 즉시 정지가 필요해 보이면 DANGER를 사용하세요.""".trimIndent()
    }

    private fun parseErrorMessage(code: Int, body: String): String {
        val defaultMessage = if (code == 401) {
            "Claude API Key가 올바르지 않습니다."
        } else {
            "Claude API 오류 ($code)"
        }
        return try {
            JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: defaultMessage
        } catch (_: Exception) {
            defaultMessage
        }
    }

    private fun extractResponseText(json: JSONObject): String {
        val blocks = json.optJSONArray("content") ?: return ""
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") {
                return block.optString("text")
            }
        }
        return blocks.optJSONObject(0)?.optString("text").orEmpty()
    }

    private fun stripJsonFences(text: String): String {
        return text
            .trim()
            .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")
            .trim()
    }

    private fun JSONObject.optStringArray(name: String): List<String> {
        val array = optJSONArray(name) ?: return emptyList()
        val values = mutableListOf<String>()
        for (i in 0 until array.length()) {
            array.optString(i).takeIf { it.isNotBlank() }?.let(values::add)
        }
        return values
    }

    companion object {
        private const val CLAUDE_API_URL = "https://api.anthropic.com/v1/messages"
    }
}

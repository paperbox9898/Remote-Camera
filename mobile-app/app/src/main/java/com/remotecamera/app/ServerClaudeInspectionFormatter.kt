package com.remotecamera.app

import org.json.JSONObject

fun formatServerClaudeInspectionDetail(json: JSONObject?): String? {
    if (json == null) return null
    if (json.optBoolean("skipped", false)) {
        val reason = json.optString("reason", "")
        if (reason == "disabled") {
            return null
        }
        val seconds = json.optDouble("next_available_in_seconds", -1.0)
        return if (seconds >= 0.0) {
            "Claude 서버 검사 대기: ${seconds.toInt()}초 후 재시도"
        } else {
            json.optString("message", "Claude 서버 검사 대기 중")
        }
    }
    if (json.optBoolean("error", false)) {
        return "Claude 서버 검사 실패: ${json.optString("message", "알 수 없는 오류").take(120)}"
    }

    val status = json.optString("status", "WATCH")
    val severity = json.optString("severity", "MEDIUM")
    val summary = json.optString("summary", "요약 없음")
    val recommendation = json.optStringList("recommendations").firstOrNull()
    val evidence = json.optStringList("evidence").firstOrNull()
    val reference = json.optStringList("reference_matches").firstOrNull()
    val falsePositive = if (json.optBoolean("false_positive_likely", false)) " / 오탐 가능성 있음" else ""

    val details = listOfNotNull(
        "Claude 서버 $status/$severity: ${summary.take(120)}$falsePositive",
        evidence?.let { "근거: ${it.take(90)}" },
        reference?.let { "기준: ${it.take(90)}" },
        recommendation?.let { "조치: ${it.take(90)}" },
    )
    return details.joinToString("\n")
}

fun JSONObject.optStringList(name: String): List<String> {
    val array = optJSONArray(name) ?: return emptyList()
    val values = mutableListOf<String>()
    for (i in 0 until array.length()) {
        array.optString(i).takeIf { it.isNotBlank() }?.let(values::add)
    }
    return values
}

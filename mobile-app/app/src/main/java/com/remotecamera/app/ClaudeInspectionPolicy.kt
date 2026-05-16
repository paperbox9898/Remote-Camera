package com.remotecamera.app

import org.json.JSONObject

fun shouldRunClientClaudeFallback(
    clientClaudeEnabled: Boolean,
    claudeApiKey: String,
    serverClaudeJson: JSONObject?,
): Boolean {
    if (!clientClaudeEnabled) return false
    if (claudeApiKey.trim().isEmpty()) return false
    if (serverClaudeJson == null) return true
    if (serverClaudeJson.optBoolean("error", false)) return true
    if (serverClaudeJson.optBoolean("skipped", false)) {
        return serverClaudeJson.optString("reason") == "disabled"
    }
    return false
}

fun shouldOfferClaudeInspectionPrompt(
    clientClaudeEnabled: Boolean,
    claudeApiKey: String,
    serverClaudeJson: JSONObject?,
    promptInFlight: Boolean,
    nowMs: Long,
    lastPromptAtMs: Long,
    cooldownMs: Long,
): Boolean {
    if (promptInFlight) return false
    if (!shouldRunClientClaudeFallback(clientClaudeEnabled, claudeApiKey, serverClaudeJson)) return false
    if (lastPromptAtMs == 0L) return true
    return nowMs - lastPromptAtMs >= cooldownMs
}

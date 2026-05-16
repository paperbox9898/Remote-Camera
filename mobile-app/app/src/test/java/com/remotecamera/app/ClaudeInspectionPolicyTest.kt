package com.remotecamera.app

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeInspectionPolicyTest {
    @Test
    fun clientFallbackIsDisabledWhenAutomaticInspectionIsOff() {
        val disabledServerClaude = JSONObject("""{"skipped":true,"reason":"disabled"}""")

        val shouldRun = shouldRunClientClaudeFallback(
            clientClaudeEnabled = false,
            claudeApiKey = "test-key",
            serverClaudeJson = disabledServerClaude,
        )

        assertFalse(shouldRun)
    }

    @Test
    fun clientFallbackRunsWhenEnabledAndServerClaudeIsUnavailable() {
        val disabledServerClaude = JSONObject("""{"skipped":true,"reason":"disabled"}""")

        val shouldRun = shouldRunClientClaudeFallback(
            clientClaudeEnabled = true,
            claudeApiKey = "test-key",
            serverClaudeJson = disabledServerClaude,
        )

        assertTrue(shouldRun)
    }

    @Test
    fun clientFallbackDoesNotRunWithoutApiKey() {
        val shouldRun = shouldRunClientClaudeFallback(
            clientClaudeEnabled = true,
            claudeApiKey = "",
            serverClaudeJson = null,
        )

        assertFalse(shouldRun)
    }

    @Test
    fun confirmationPromptIsSuppressedDuringCooldown() {
        val shouldPrompt = shouldOfferClaudeInspectionPrompt(
            clientClaudeEnabled = true,
            claudeApiKey = "test-key",
            serverClaudeJson = null,
            promptInFlight = false,
            nowMs = 15_000L,
            lastPromptAtMs = 10_000L,
            cooldownMs = 60_000L,
        )

        assertFalse(shouldPrompt)
    }

    @Test
    fun confirmationPromptIsOfferedWhenEnabledAndCooldownPassed() {
        val shouldPrompt = shouldOfferClaudeInspectionPrompt(
            clientClaudeEnabled = true,
            claudeApiKey = "test-key",
            serverClaudeJson = null,
            promptInFlight = false,
            nowMs = 80_000L,
            lastPromptAtMs = 10_000L,
            cooldownMs = 60_000L,
        )

        assertTrue(shouldPrompt)
    }
}

package com.remotecamera.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerClaudeInspectionFormatterTest {
    @Test
    fun disabledServerClaudeProducesNoUserMessageInAppOnlyMode() {
        val message = formatServerClaudeInspectionDetail(
            JSONObject("""{"skipped":true,"reason":"disabled"}"""),
        )

        assertNull(message)
    }

    @Test
    fun serverClaudeErrorStillProducesFailureMessage() {
        val message = formatServerClaudeInspectionDetail(
            JSONObject("""{"error":true,"message":"rate limit"}"""),
        )

        assertEquals("Claude 서버 검사 실패: rate limit", message)
    }
}

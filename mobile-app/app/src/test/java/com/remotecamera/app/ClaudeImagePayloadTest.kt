package com.remotecamera.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeImagePayloadTest {
    @Test
    fun estimatesBase64EncodedLength() {
        assertEquals(0, estimatedBase64Length(0))
        assertEquals(4, estimatedBase64Length(1))
        assertEquals(4, estimatedBase64Length(2))
        assertEquals(4, estimatedBase64Length(3))
        assertEquals(8, estimatedBase64Length(4))
    }

    @Test
    fun detectsImagesThatExceedClaudeBase64Limit() {
        assertTrue(isWithinClaudeImageBase64Limit(CLAUDE_IMAGE_MAX_RAW_BYTES))
        assertFalse(isWithinClaudeImageBase64Limit(CLAUDE_IMAGE_MAX_RAW_BYTES + 1))
    }

    @Test
    fun rawByteLimitStaysBelowFiveMegabyteBase64Limit() {
        assertTrue(estimatedBase64Length(CLAUDE_IMAGE_MAX_RAW_BYTES) <= CLAUDE_IMAGE_MAX_BASE64_BYTES)
        assertTrue(estimatedBase64Length(8_304_420) > CLAUDE_IMAGE_MAX_BASE64_BYTES)
    }
}

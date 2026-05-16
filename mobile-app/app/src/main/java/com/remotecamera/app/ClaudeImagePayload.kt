package com.remotecamera.app

const val CLAUDE_IMAGE_MAX_BASE64_BYTES = 5 * 1024 * 1024
const val CLAUDE_IMAGE_MAX_RAW_BYTES = (CLAUDE_IMAGE_MAX_BASE64_BYTES / 4) * 3

data class ClaudeImagePayload(
    val bytes: ByteArray,
    val mimeType: String,
)

fun estimatedBase64Length(rawByteCount: Int): Int {
    if (rawByteCount <= 0) return 0
    return ((rawByteCount + 2) / 3) * 4
}

fun isWithinClaudeImageBase64Limit(rawByteCount: Int): Boolean {
    return estimatedBase64Length(rawByteCount) <= CLAUDE_IMAGE_MAX_BASE64_BYTES
}

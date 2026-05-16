package com.remotecamera.app

object AppSettings {
    const val PREFS_NAME = "remote_camera_settings"
    const val KEY_SERVER_URL = "server_url"
    const val KEY_API_KEY = "api_key"
    const val KEY_CLAUDE_API_KEY = "claude_api_key"
    const val KEY_CLAUDE_PROMPT_ENABLED = "claude_auto_inspection"
    const val KEY_CLAUDE_PROMPT_TEXT = "claude_prompt_text"
    const val DEFAULT_SERVER_URL = "https://chamin.taile54870.ts.net"
}

fun normalizeServerUrl(value: String): String = value.trim().trimEnd('/')

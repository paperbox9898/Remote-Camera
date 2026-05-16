package com.remotecamera.app

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private lateinit var etServerUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etClaudeApiKey: EditText
    private lateinit var etClaudePrompt: EditText
    private lateinit var cbClaudePrompt: CheckBox
    private lateinit var btnSaveSettings: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etServerUrl = findViewById(R.id.etServerUrl)
        etApiKey = findViewById(R.id.etApiKey)
        etClaudeApiKey = findViewById(R.id.etClaudeApiKey)
        etClaudePrompt = findViewById(R.id.etClaudePrompt)
        cbClaudePrompt = findViewById(R.id.cbClaudePrompt)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)

        loadSettings()
        btnSaveSettings.setOnClickListener {
            saveSettings()
            Toast.makeText(this, "설정을 저장했습니다.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(AppSettings.PREFS_NAME, MODE_PRIVATE)
        etServerUrl.setText(prefs.getString(AppSettings.KEY_SERVER_URL, AppSettings.DEFAULT_SERVER_URL))
        etApiKey.setText(prefs.getString(AppSettings.KEY_API_KEY, ""))
        etClaudeApiKey.setText(prefs.getString(AppSettings.KEY_CLAUDE_API_KEY, BuildConfig.CLAUDE_API_KEY))
        etClaudePrompt.setText(prefs.getString(AppSettings.KEY_CLAUDE_PROMPT_TEXT, ""))
        cbClaudePrompt.isChecked = prefs.getBoolean(AppSettings.KEY_CLAUDE_PROMPT_ENABLED, false)
    }

    private fun saveSettings() {
        getSharedPreferences(AppSettings.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(AppSettings.KEY_SERVER_URL, normalizeServerUrl(etServerUrl.text.toString()))
            .putString(AppSettings.KEY_API_KEY, etApiKey.text.toString())
            .putString(AppSettings.KEY_CLAUDE_API_KEY, etClaudeApiKey.text.toString())
            .putString(AppSettings.KEY_CLAUDE_PROMPT_TEXT, etClaudePrompt.text.toString())
            .putBoolean(AppSettings.KEY_CLAUDE_PROMPT_ENABLED, cbClaudePrompt.isChecked)
            .apply()
    }
}

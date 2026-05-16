package com.remotecamera.app

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ResultsActivity : AppCompatActivity() {

    private lateinit var tvHistoryStatus: TextView
    private lateinit var btnRefreshHistory: Button
    private lateinit var tvSelectedImage: TextView
    private lateinit var ivSelectedResult: ImageView
    private lateinit var llHistoryList: LinearLayout

    private var serverUrl = ""
    private var apiKey = ""

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_results)

        supportActionBar?.title = "결과 리스트"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        tvHistoryStatus = findViewById(R.id.tvHistoryStatus)
        btnRefreshHistory = findViewById(R.id.btnRefreshHistory)
        tvSelectedImage = findViewById(R.id.tvSelectedImage)
        ivSelectedResult = findViewById(R.id.ivSelectedResult)
        llHistoryList = findViewById(R.id.llHistoryList)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)
            ?: prefs.getString(KEY_SERVER_URL, "").orEmpty()
        apiKey = intent.getStringExtra(EXTRA_API_KEY)
            ?: prefs.getString(KEY_API_KEY, "").orEmpty()
        serverUrl = serverUrl.trim().trimEnd('/')

        btnRefreshHistory.setOnClickListener {
            loadHistory()
        }

        loadHistory()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadHistory() {
        if (serverUrl.isBlank()) {
            tvHistoryStatus.text = "서버 URL이 없습니다."
            return
        }

        btnRefreshHistory.isEnabled = false
        tvHistoryStatus.text = "결과 이력을 불러오는 중..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val requestBuilder = Request.Builder()
                    .url("$serverUrl/history?limit=100")
                if (apiKey.isNotBlank()) {
                    requestBuilder.addHeader("X-API-Key", apiKey)
                }

                client.newCall(requestBuilder.build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    withContext(Dispatchers.Main) {
                        btnRefreshHistory.isEnabled = true
                        if (response.isSuccessful) {
                            renderHistory(JSONObject(body))
                        } else if (response.code == 401) {
                            tvHistoryStatus.text = "인증 실패: API Key를 확인하세요."
                        } else {
                            tvHistoryStatus.text = "이력 로드 실패: ${response.code} ${body.take(120)}"
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    btnRefreshHistory.isEnabled = true
                    tvHistoryStatus.text = "이력 로드 실패: ${e.message ?: "서버 연결을 확인하세요."}"
                }
            }
        }
    }

    private fun renderHistory(json: JSONObject) {
        llHistoryList.removeAllViews()

        val items = json.optJSONArray("items")
        val count = items?.length() ?: 0
        tvHistoryStatus.text = "최신 결과 ${count}개"

        if (items == null || count == 0) {
            llHistoryList.addView(historyText("아직 저장된 결과가 없습니다.", 15f, "#D1D5DB"))
            return
        }

        for (i in 0 until count) {
            val item = items.optJSONObject(i) ?: continue
            addHistoryRow(item)
        }
    }

    private fun addHistoryRow(item: JSONObject) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground("#1F2937", "#374151")
        }

        val status = item.optString("status", "UNKNOWN")
        row.addView(
            historyText(
                "${sourceLabel(item.optString("source"))} · $status",
                18f,
                statusColor(status),
                bold = true,
            )
        )

        row.addView(
            historyText(
                "${formatTime(item.optString("time"))} / UID ${item.optString("uid", "-")}",
                13f,
                "#9CA3AF",
            )
        )

        val count = item.optString("count", "")
        if (count.isNotBlank()) {
            row.addView(historyText("감지 인원: ${count}명", 14f, "#D1D5DB"))
        }

        item.optJSONObject("claude_inspection")?.let { claude ->
            row.addView(historyText(formatClaude(claude), 14f, "#FDE68A"))
        }

        val imagePath = item.optString("result_image", "")
        if (imagePath.isNotBlank()) {
            val imageButton = Button(this).apply {
                text = "이미지 보기"
                textSize = 14f
                setTextColor(Color.WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#374151"))
                setOnClickListener {
                    loadResultImage(imagePath, item.optString("time"))
                }
            }
            row.addView(imageButton)
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dp(10)
        }
        llHistoryList.addView(row, params)
    }

    private fun loadResultImage(resultPath: String, time: String) {
        tvSelectedImage.visibility = View.VISIBLE
        ivSelectedResult.visibility = View.VISIBLE
        tvSelectedImage.text = "${formatTime(time)} 이미지 로딩 중..."
        ivSelectedResult.setImageDrawable(null)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val encodedPath = resultPath
                    .replace("\\", "/")
                    .split("/")
                    .joinToString("/") { Uri.encode(it) }
                val urlBuilder = StringBuilder("$serverUrl/files/$encodedPath")
                if (apiKey.isNotBlank()) {
                    urlBuilder.append("?key=").append(Uri.encode(apiKey))
                }

                client.newCall(Request.Builder().url(urlBuilder.toString()).build()).execute().use { response ->
                    val bytes = response.body?.bytes()
                    val bitmap = if (response.isSuccessful && bytes != null) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } else {
                        null
                    }

                    withContext(Dispatchers.Main) {
                        if (bitmap != null) {
                            tvSelectedImage.text = "${formatTime(time)} 결과 이미지"
                            ivSelectedResult.setImageBitmap(bitmap)
                        } else {
                            tvSelectedImage.text = "이미지 로드 실패: ${response.code}"
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvSelectedImage.text = "이미지 로드 실패: ${e.message ?: "서버 연결을 확인하세요."}"
                }
            }
        }
    }

    private fun historyText(text: String, sizeSp: Float, color: String, bold: Boolean = false): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(Color.parseColor(color))
            if (bold) {
                typeface = Typeface.DEFAULT_BOLD
            }
            setPadding(0, 0, 0, dp(6))
        }
    }

    private fun roundedBackground(fill: String, stroke: String): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fill))
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), Color.parseColor(stroke))
        }
    }

    private fun sourceLabel(source: String): String {
        return when (source) {
            "inspect" -> "이미지 판정"
            "claude" -> "Claude 검사"
            else -> "서버 결과"
        }
    }

    private fun statusColor(status: String): String {
        return when (status.uppercase()) {
            "OK", "SAFE" -> "#34D399"
            "NG", "DANGER", "ERROR" -> "#F87171"
            "WATCH", "UNKNOWN" -> "#FBBF24"
            else -> "#D1D5DB"
        }
    }

    private fun formatClaude(claude: JSONObject): String {
        val status = claude.optString("status", "UNKNOWN")
        val severity = claude.optString("severity", "")
        val summary = claude.optString("summary", "")
        val label = if (severity.isBlank()) status else "$status/$severity"
        return if (summary.isBlank()) {
            "Claude: $label"
        } else {
            "Claude: $label · ${summary.take(120)}"
        }
    }

    private fun formatTime(value: String): String {
        return if (value.length >= 15) {
            "${value.substring(0, 4)}-${value.substring(4, 6)}-${value.substring(6, 8)} " +
                "${value.substring(9, 11)}:${value.substring(11, 13)}:${value.substring(13, 15)}"
        } else {
            value.ifBlank { "-" }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val PREFS_NAME = "remote_camera_settings"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_API_KEY = "api_key"
        private const val EXTRA_SERVER_URL = "server_url"
        private const val EXTRA_API_KEY = "api_key"
    }
}

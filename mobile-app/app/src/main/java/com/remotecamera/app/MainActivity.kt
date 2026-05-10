package com.remotecamera.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var etServerUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var btnCapture: Button
    private lateinit var tvResult: TextView
    private lateinit var tvDetail: TextView
    private lateinit var ivResultImage: ImageView

    private var photoFile: File? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etServerUrl = findViewById(R.id.etServerUrl)
        etApiKey = findViewById(R.id.etApiKey)
        btnCapture = findViewById(R.id.btnCapture)
        tvResult = findViewById(R.id.tvResult)
        tvDetail = findViewById(R.id.tvDetail)
        ivResultImage = findViewById(R.id.ivResultImage)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        etServerUrl.setText(prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL))
        etApiKey.setText(prefs.getString(KEY_API_KEY, ""))

        btnCapture.setOnClickListener {
            if (hasCameraPermission()) launchCamera() else requestCameraPermission()
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_PERMISSION)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSION &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        } else {
            showResult("카메라 권한이 필요합니다.", android.R.color.darker_gray)
        }
    }

    @Suppress("DEPRECATION")
    private fun launchCamera() {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val file = File.createTempFile("IMG_${ts}_", ".jpg", storageDir)
        photoFile = file
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
        startActivityForResult(intent, REQ_CAPTURE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK) {
            photoFile?.let { uploadImage(it) }
        }
    }

    private fun uploadImage(file: File) {
        val serverUrl = etServerUrl.text.toString().trimEnd('/')
        val apiKey = etApiKey.text.toString()

        if (serverUrl.isEmpty()) {
            showResult("서버 URL을 입력하세요.", android.R.color.darker_gray)
            return
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, serverUrl)
            .putString(KEY_API_KEY, apiKey)
            .apply()

        btnCapture.isEnabled = false
        ivResultImage.setImageDrawable(null)
        showResult("분석 중...", android.R.color.darker_gray, "서버에 이미지를 업로드하고 있습니다.")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "image", file.name,
                        file.asRequestBody("image/jpeg".toMediaType())
                    )
                    .build()

                val reqBuilder = Request.Builder()
                    .url("$serverUrl/inspect")
                    .post(requestBody)

                if (apiKey.isNotEmpty()) {
                    reqBuilder.addHeader("X-API-Key", apiKey)
                }

                val response = client.newCall(reqBuilder.build()).execute()
                val body = response.body?.string() ?: ""

                withContext(Dispatchers.Main) {
                    btnCapture.isEnabled = true
                    when {
                        response.isSuccessful -> {
                            val json = JSONObject(body)
                            val alarm = json.optBoolean("alarm", false)
                            val status = json.optString("status", "UNKNOWN")
                            val detections = json.optJSONArray("detections")
                            val count = detections?.length() ?: 0
                            val resultImage = json.optString("result_image", "")
                            if (alarm || status == "NG") {
                                vibrateAlarm()
                                showResult("불량/위험 감지", android.R.color.holo_red_dark, "감지 인원: ${count}명")
                            } else {
                                showResult("정상", android.R.color.holo_green_dark, "감지 인원: ${count}명")
                            }
                            if (resultImage.isNotEmpty()) {
                                loadResultImage(serverUrl, apiKey, resultImage)
                            }
                        }
                        response.code == 401 ->
                            showResult("인증 실패", android.R.color.holo_orange_dark, "API Key를 확인하세요.")
                        else ->
                            showResult("서버 오류: ${response.code}", android.R.color.holo_orange_dark, body.take(160))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    btnCapture.isEnabled = true
                    showResult("연결 실패", android.R.color.holo_orange_dark, e.message ?: "서버 URL과 Tailscale 연결을 확인하세요.")
                }
            }
        }
    }

    private fun loadResultImage(serverUrl: String, apiKey: String, resultPath: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val encodedPath = resultPath
                    .split("/")
                    .joinToString("/") { Uri.encode(it) }
                val urlBuilder = StringBuilder("$serverUrl/files/$encodedPath")
                if (apiKey.isNotEmpty()) {
                    urlBuilder.append("?key=").append(Uri.encode(apiKey))
                }
                val request = Request.Builder()
                    .url(urlBuilder.toString())
                    .build()
                val response = client.newCall(request).execute()
                val bytes = response.body?.bytes()
                val bitmap = if (response.isSuccessful && bytes != null) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } else {
                    null
                }
                withContext(Dispatchers.Main) {
                    if (bitmap != null) {
                        ivResultImage.setImageBitmap(bitmap)
                    } else {
                        tvDetail.text = "${tvDetail.text}\n결과 이미지 로드 실패: ${response.code}"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvDetail.text = "${tvDetail.text}\n결과 이미지 로드 실패: ${e.message}"
                }
            }
        }
    }

    private fun showResult(text: String, colorRes: Int, detail: String = "") {
        tvResult.text = text
        tvDetail.text = detail
        tvResult.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun vibrateAlarm() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VibratorManager::class.java)
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 250, 120, 250), -1)
        }
    }

    companion object {
        private const val REQ_PERMISSION = 1001
        private const val REQ_CAPTURE = 1002
        private const val PREFS_NAME = "remote_camera_settings"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_API_KEY = "api_key"
        private const val DEFAULT_SERVER_URL = "https://chamin.taile54870.ts.net"
    }
}

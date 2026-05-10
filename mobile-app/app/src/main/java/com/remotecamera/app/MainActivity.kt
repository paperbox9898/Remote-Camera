package com.remotecamera.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
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

        btnCapture.isEnabled = false
        showResult("분석 중...", android.R.color.darker_gray)

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
                            if (alarm || status == "NG") {
                                showResult("불량/위험 감지", android.R.color.holo_red_dark)
                            } else {
                                showResult("정상", android.R.color.holo_green_dark)
                            }
                        }
                        response.code == 401 ->
                            showResult("인증 실패 (API Key 확인)", android.R.color.holo_orange_dark)
                        else ->
                            showResult("서버 오류: ${response.code}", android.R.color.holo_orange_dark)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    btnCapture.isEnabled = true
                    showResult("연결 실패: ${e.message}", android.R.color.holo_orange_dark)
                }
            }
        }
    }

    private fun showResult(text: String, colorRes: Int) {
        tvResult.text = text
        tvResult.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    companion object {
        private const val REQ_PERMISSION = 1001
        private const val REQ_CAPTURE = 1002
    }
}

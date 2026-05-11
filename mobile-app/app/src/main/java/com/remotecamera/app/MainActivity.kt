package com.remotecamera.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var etServerUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var btnCapture: Button
    private lateinit var btnLive: Button
    private lateinit var previewView: PreviewView
    private lateinit var tvResult: TextView
    private lateinit var tvDetail: TextView
    private lateinit var ivResultImage: ImageView

    private lateinit var cameraExecutor: ExecutorService
    private var photoFile: File? = null
    private var webSocket: WebSocket? = null
    private var liveMode = false
    private var pendingLiveStart = false
    private var awaitingStreamResponse = false
    private var lastFrameSentAt = 0L
    private var liveFrameCount = 0

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etServerUrl = findViewById(R.id.etServerUrl)
        etApiKey = findViewById(R.id.etApiKey)
        btnCapture = findViewById(R.id.btnCapture)
        btnLive = findViewById(R.id.btnLive)
        previewView = findViewById(R.id.previewView)
        tvResult = findViewById(R.id.tvResult)
        tvDetail = findViewById(R.id.tvDetail)
        ivResultImage = findViewById(R.id.ivResultImage)
        cameraExecutor = Executors.newSingleThreadExecutor()

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        etServerUrl.setText(prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL))
        etApiKey.setText(prefs.getString(KEY_API_KEY, ""))

        btnCapture.setOnClickListener {
            if (hasCameraPermission()) launchCamera() else requestCameraPermission(startLiveAfterGrant = false)
        }

        btnLive.setOnClickListener {
            if (liveMode) {
                stopLiveMode()
            } else if (hasCameraPermission()) {
                startLiveMode()
            } else {
                requestCameraPermission(startLiveAfterGrant = true)
            }
        }
    }

    override fun onDestroy() {
        stopLiveMode()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission(startLiveAfterGrant: Boolean) {
        pendingLiveStart = startLiveAfterGrant
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_PERMISSION)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSION &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            if (pendingLiveStart) startLiveMode() else launchCamera()
        } else {
            showResult("카메라 권한이 필요합니다.", android.R.color.darker_gray)
        }
        pendingLiveStart = false
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

    private fun startLiveMode() {
        val serverUrl = normalizedServerUrl()
        if (serverUrl.isEmpty()) {
            showResult("서버 URL을 입력하세요.", android.R.color.darker_gray)
            return
        }
        saveSettings(serverUrl, etApiKey.text.toString())
        liveMode = true
        awaitingStreamResponse = false
        liveFrameCount = 0
        btnLive.text = "실시간 감시 중지"
        btnCapture.isEnabled = false
        ivResultImage.setImageDrawable(null)
        showResult("실시간 연결 중...", android.R.color.darker_gray, "카메라 프리뷰를 준비하고 있습니다.")
        startCameraPreview()
        openStreamSocket(serverUrl, etApiKey.text.toString())
    }

    private fun stopLiveMode() {
        liveMode = false
        awaitingStreamResponse = false
        webSocket?.close(1000, "live stopped")
        webSocket = null
        btnLive.text = "실시간 감시 시작"
        btnCapture.isEnabled = true
        try {
            ProcessCameraProvider.getInstance(this).get().unbindAll()
        } catch (_: Exception) {
        }
    }

    private fun startCameraPreview() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        handleLiveFrame(imageProxy)
                    }
                }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun openStreamSocket(serverUrl: String, apiKey: String) {
        val streamUrl = buildStreamUrl(serverUrl, apiKey)
        val request = Request.Builder().url(streamUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread {
                    showResult("실시간 감시 중", android.R.color.darker_gray, "서버에 연결되었습니다.")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                awaitingStreamResponse = false
                val json = JSONObject(text)
                runOnUiThread {
                    if (json.has("error")) {
                        showResult("스트림 오류", android.R.color.holo_orange_dark, json.optString("error"))
                        return@runOnUiThread
                    }
                    val alarm = json.optBoolean("alarm", false)
                    val status = json.optString("status", "UNKNOWN")
                    val count = json.optJSONArray("detections")?.length() ?: 0
                    liveFrameCount += 1
                    if (alarm || status == "NG") {
                        vibrateAlarm()
                        showResult(
                            "불량/위험 감지",
                            android.R.color.holo_red_dark,
                            "실시간 프레임: ${liveFrameCount} / 감지 인원: ${count}명",
                        )
                    } else {
                        showResult(
                            "정상",
                            android.R.color.holo_green_dark,
                            "실시간 프레임: ${liveFrameCount} / 감지 인원: ${count}명",
                        )
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                awaitingStreamResponse = false
                runOnUiThread {
                    if (liveMode) {
                        stopLiveMode()
                        showResult("실시간 연결 실패", android.R.color.holo_orange_dark, t.message ?: "서버 연결을 확인하세요.")
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                awaitingStreamResponse = false
            }
        })
    }

    private fun handleLiveFrame(imageProxy: ImageProxy) {
        try {
            if (!liveMode || webSocket == null || awaitingStreamResponse) return
            val now = System.currentTimeMillis()
            if (now - lastFrameSentAt < LIVE_FRAME_INTERVAL_MS) return
            lastFrameSentAt = now
            val jpeg = imageProxyToJpegBytes(imageProxy, LIVE_JPEG_QUALITY)
            awaitingStreamResponse = webSocket?.send(jpeg.toByteString()) == true
        } catch (_: Exception) {
            awaitingStreamResponse = false
        } finally {
            imageProxy.close()
        }
    }

    private fun imageProxyToJpegBytes(image: ImageProxy, quality: Int): ByteArray {
        val nv21 = yuv420ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
        val bytes = out.toByteArray()
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return bytes

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val rotatedOut = ByteArrayOutputStream()
        rotated.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, rotatedOut)
        bitmap.recycle()
        rotated.recycle()
        return rotatedOut.toByteArray()
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 4
        val nv21 = ByteArray(ySize + uvSize * 2)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        var pos = 0
        val yRowStride = yPlane.rowStride
        val yRow = ByteArray(yRowStride)
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            val length = minOf(yRowStride, yBuffer.remaining())
            yBuffer.get(yRow, 0, length)
            System.arraycopy(yRow, 0, nv21, pos, width)
            pos += width
        }

        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val index = row * uvRowStride + col * uvPixelStride
                nv21[pos++] = vBuffer.get(index)
                nv21[pos++] = uBuffer.get(index)
            }
        }

        return nv21
    }

    private fun uploadImage(file: File) {
        val serverUrl = normalizedServerUrl()
        val apiKey = etApiKey.text.toString()

        if (serverUrl.isEmpty()) {
            showResult("서버 URL을 입력하세요.", android.R.color.darker_gray)
            return
        }
        saveSettings(serverUrl, apiKey)

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

    private fun normalizedServerUrl() = etServerUrl.text.toString().trim().trimEnd('/')

    private fun saveSettings(serverUrl: String, apiKey: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, serverUrl)
            .putString(KEY_API_KEY, apiKey)
            .apply()
    }

    private fun buildStreamUrl(serverUrl: String, apiKey: String): String {
        val wsBase = when {
            serverUrl.startsWith("https://") -> "wss://" + serverUrl.removePrefix("https://")
            serverUrl.startsWith("http://") -> "ws://" + serverUrl.removePrefix("http://")
            else -> "wss://$serverUrl"
        }
        val separator = if (wsBase.contains("?")) "&" else "?"
        val keyQuery = if (apiKey.isNotEmpty()) "${separator}key=${Uri.encode(apiKey)}" else ""
        return "$wsBase/stream$keyQuery"
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
        private const val LIVE_FRAME_INTERVAL_MS = 500L
        private const val LIVE_JPEG_QUALITY = 65
    }
}

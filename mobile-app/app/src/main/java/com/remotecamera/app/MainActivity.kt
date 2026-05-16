package com.remotecamera.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var btnCapture: Button
    private lateinit var btnLive: Button
    private lateinit var btnClearArea: Button
    private lateinit var btnUploadReference: Button
    private lateinit var btnResults: Button
    private lateinit var btnSettings: Button
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: DetectionOverlayView
    private lateinit var tvResult: TextView
    private lateinit var tvDetail: TextView
    private lateinit var tvSettingsSummary: TextView
    private lateinit var ivResultImage: ImageView

    private lateinit var cameraExecutor: ExecutorService
    private var webSocket: WebSocket? = null
    private var liveMode = false
    private var pendingLiveStart = false
    private var awaitingStreamResponse = false
    private var lastFrameSentAt = 0L
    private var lastFpsUpdatedAt = 0L
    private var framesSinceFpsUpdate = 0
    private var liveFps = 0
    @Volatile
    private var lastSentLiveFrameJpeg: ByteArray? = null
    private var lastClaudePromptAt = 0L
    private var lastClaudeInspectionAt = 0L
    private var claudeInspectionInFlight = false
    private var claudePromptInFlight = false
    private var latestClaudeDetail = ""
    private var latestClaudeDetailAt = 0L

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private val claudeInspector by lazy { ClaudeInspector(client) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnCapture = findViewById(R.id.btnCapture)
        btnLive = findViewById(R.id.btnLive)
        btnClearArea = findViewById(R.id.btnClearArea)
        btnUploadReference = findViewById(R.id.btnUploadReference)
        btnResults = findViewById(R.id.btnResults)
        btnSettings = findViewById(R.id.btnSettings)
        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        tvResult = findViewById(R.id.tvResult)
        tvDetail = findViewById(R.id.tvDetail)
        tvSettingsSummary = findViewById(R.id.tvSettingsSummary)
        ivResultImage = findViewById(R.id.ivResultImage)
        cameraExecutor = Executors.newSingleThreadExecutor()

        btnCapture.setOnClickListener {
            openInspectionImagePicker()
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

        btnClearArea.setOnClickListener {
            overlayView.clearArea()
            tvDetail.text = "감시 영역을 초기화했습니다."
            if (liveMode) {
                stopLiveMode()
                startLiveMode()
            }
        }

        btnUploadReference.setOnClickListener {
            openReferencePicker()
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnResults.setOnClickListener {
            val serverUrl = normalizedServerUrl()
            if (serverUrl.isEmpty()) {
                showResult("서버 URL을 입력하세요.", android.R.color.darker_gray)
                return@setOnClickListener
            }
            val apiKey = serverApiKey()
            startActivity(
                Intent(this, ResultsActivity::class.java)
                    .putExtra(EXTRA_SERVER_URL, serverUrl)
                    .putExtra(EXTRA_API_KEY, apiKey)
            )
        }

        overlayView.onAreaChanged = {
            tvDetail.text = if (overlayView.areaPointCount >= 3) {
                "감시 영역: ${overlayView.areaPointCount}개 점"
            } else {
                "감시 영역 점을 ${3 - overlayView.areaPointCount}개 더 찍으세요."
            }
            if (liveMode) {
                stopLiveMode()
                startLiveMode()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateSettingsSummary()
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
            if (pendingLiveStart) startLiveMode()
        } else {
            showResult("카메라 권한이 필요합니다.", android.R.color.darker_gray)
        }
        pendingLiveStart = false
    }

    @Suppress("DEPRECATION")
    private fun openInspectionImagePicker() {
        val serverUrl = normalizedServerUrl()
        if (serverUrl.isEmpty()) {
            showResult("서버 URL을 입력하세요.", android.R.color.darker_gray)
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png", "image/webp"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQ_IMAGE_PICK)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMAGE_PICK && resultCode == RESULT_OK) {
            data?.data?.let { uploadInspectionImage(it) }
        } else if (requestCode == REQ_REFERENCE_PICK && resultCode == RESULT_OK) {
            data?.data?.let { uploadReference(it) }
        }
    }

    private fun startLiveMode() {
        val serverUrl = normalizedServerUrl()
        if (serverUrl.isEmpty()) {
            showResult("서버 URL을 입력하세요.", android.R.color.darker_gray)
            return
        }
        liveMode = true
        awaitingStreamResponse = false
        lastFpsUpdatedAt = 0L
        framesSinceFpsUpdate = 0
        liveFps = 0
        overlayView.setDetections(emptyList(), 0, 0)
        btnLive.text = "실시간 중지"
        btnCapture.isEnabled = false
        ivResultImage.setImageDrawable(null)
        showResult("실시간 연결 중...", android.R.color.darker_gray, "카메라 프리뷰를 준비하고 있습니다.")
        startCameraPreview()
        openStreamSocket(serverUrl, serverApiKey())
    }

    private fun stopLiveMode() {
        liveMode = false
        awaitingStreamResponse = false
        webSocket?.close(1000, "live stopped")
        webSocket = null
        btnLive.text = "실시간 시작"
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
                    val detections = json.optJSONArray("detections")
                    val count = detections?.length() ?: 0
                    overlayView.setDetections(
                        parseDetectionBoxes(json),
                        json.optInt("frame_width", 0),
                        json.optInt("frame_height", 0),
                    )
                    updateLiveFps()
                    if (alarm || status == "NG") {
                        vibrateAlarm()
                        showResult(
                            "불량/위험 감지",
                            android.R.color.holo_red_dark,
                            "실시간 FPS: ${liveFps} / 감지 인원: ${count}명",
                        )
                        val serverClaudeJson = json.optJSONObject("claude_inspection")
                        val serverClaude = formatServerClaudeInspectionDetail(serverClaudeJson)
                        if (serverClaude != null) {
                            appendClaudeDetail(serverClaude)
                        }
                        if (shouldOfferClaudeInspectionPrompt(serverClaudeJson)) {
                            offerClaudeInspection(
                                jpegBytes = lastSentLiveFrameJpeg,
                                imageMimeType = "image/jpeg",
                                detectionCount = count,
                                source = "실시간 감시",
                                yoloStatus = status,
                            )
                        }
                    } else {
                        showResult(
                            "정상",
                            android.R.color.holo_green_dark,
                            "실시간 FPS: ${liveFps} / 감지 인원: ${count}명",
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
            val sent = webSocket?.send(jpeg.toByteString()) == true
            if (sent) {
                lastSentLiveFrameJpeg = jpeg
            }
            awaitingStreamResponse = sent
        } catch (_: Exception) {
            awaitingStreamResponse = false
        } finally {
            imageProxy.close()
        }
    }

    private fun updateLiveFps() {
        val now = System.currentTimeMillis()
        if (lastFpsUpdatedAt == 0L) {
            lastFpsUpdatedAt = now
            liveFps = 0
            return
        }

        framesSinceFpsUpdate += 1
        val elapsedMs = now - lastFpsUpdatedAt
        if (elapsedMs >= FPS_UPDATE_INTERVAL_MS) {
            liveFps = ((framesSinceFpsUpdate * 1000f) / elapsedMs).toInt()
            framesSinceFpsUpdate = 0
            lastFpsUpdatedAt = now
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

    private fun openReferencePicker() {
        val serverUrl = normalizedServerUrl()
        if (serverUrl.isEmpty()) {
            showResult("서버 URL 필요", android.R.color.darker_gray, "서버 URL을 먼저 입력하세요.")
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "text/plain",
                    "text/markdown",
                    "text/csv",
                    "application/json",
                    "application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "image/jpeg",
                    "image/png",
                    "image/webp",
                ),
            )
        }
        startActivityForResult(intent, REQ_REFERENCE_PICK)
    }

    private fun uploadReference(uri: Uri) {
        val serverUrl = normalizedServerUrl()
        val apiKey = serverApiKey()
        val name = displayName(uri)
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

        btnUploadReference.isEnabled = false
        showResult("참조 업로드 중", android.R.color.darker_gray, name)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("파일을 읽을 수 없습니다.")
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "document",
                        name,
                        bytes.toRequestBody(mimeType.toMediaType()),
                    )
                    .build()

                val reqBuilder = Request.Builder()
                    .url("$serverUrl/references")
                    .post(requestBody)

                if (apiKey.isNotEmpty()) {
                    reqBuilder.addHeader("X-API-Key", apiKey)
                }

                client.newCall(reqBuilder.build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    withContext(Dispatchers.Main) {
                        btnUploadReference.isEnabled = true
                        when {
                            response.isSuccessful -> {
                                val json = JSONObject(body)
                                showResult(
                                    "참조 업로드 완료",
                                    android.R.color.holo_green_dark,
                                    json.optString("name", name),
                                )
                            }
                            response.code == 401 ->
                                showResult("인증 실패", android.R.color.holo_orange_dark, "API Key를 확인하세요.")
                            else ->
                                showResult("참조 업로드 실패: ${response.code}", android.R.color.holo_orange_dark, body.take(160))
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    btnUploadReference.isEnabled = true
                    showResult("참조 업로드 실패", android.R.color.holo_orange_dark, e.message ?: "파일 또는 서버 연결을 확인하세요.")
                }
            }
        }
    }

    private fun displayName(uri: Uri, fallback: String = "file"): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                val name = cursor.getString(nameIndex)
                if (!name.isNullOrBlank()) return name
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun uploadInspectionImage(uri: Uri) {
        val serverUrl = normalizedServerUrl()
        val apiKey = serverApiKey()
        val name = displayName(uri, fallback = "inspection-image")
        val mimeType = inspectionImageMimeType(contentResolver.getType(uri).orEmpty(), name)

        if (serverUrl.isEmpty()) {
            showResult("서버 URL을 입력하세요.", android.R.color.darker_gray)
            return
        }
        if (!isSupportedInspectionImageMimeType(mimeType)) {
            showResult("이미지 형식 오류", android.R.color.holo_orange_dark, "JPEG, PNG, WebP 이미지만 판정할 수 있습니다.")
            return
        }
        btnCapture.isEnabled = false
        ivResultImage.setImageDrawable(null)
        showResult("분석 중...", android.R.color.darker_gray, "선택한 이미지를 서버에 업로드하고 있습니다.")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("이미지를 읽을 수 없습니다.")
                val selectedBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "image",
                        name,
                        bytes.toRequestBody(mimeType.toMediaType()),
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
                    if (selectedBitmap != null) {
                        ivResultImage.setImageBitmap(selectedBitmap)
                    }
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
                                val serverClaudeJson = json.optJSONObject("claude_inspection")
                                val serverClaude = formatServerClaudeInspectionDetail(serverClaudeJson)
                                if (serverClaude != null) {
                                    appendClaudeDetail(serverClaude)
                                }
                                if (shouldOfferClaudeInspectionPrompt(serverClaudeJson)) {
                                    offerClaudeInspection(
                                        jpegBytes = bytes,
                                        imageMimeType = mimeType,
                                        detectionCount = count,
                                        source = "이미지 판정",
                                        yoloStatus = status,
                                    )
                                }
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

    private fun maybeRunClaudeInspection(
        jpegBytes: ByteArray?,
        imageMimeType: String,
        detectionCount: Int,
        source: String,
        yoloStatus: String,
    ) {
        val apiKey = claudeApiKey()
        if (!isClaudePromptEnabled()) return
        if (jpegBytes == null || apiKey.isEmpty()) return

        val now = System.currentTimeMillis()
        if (claudeInspectionInFlight || now - lastClaudeInspectionAt < CLAUDE_INSPECTION_COOLDOWN_MS) {
            return
        }

        lastClaudeInspectionAt = now
        claudeInspectionInFlight = true
        appendClaudeDetail("Claude 분석 중...")

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = claudeInspector.inspect(
                    jpegBytes = jpegBytes,
                    imageMimeType = imageMimeType,
                    apiKey = apiKey,
                    context = ClaudeInspectionContext(
                        source = source,
                        yoloStatus = yoloStatus,
                        detectionCount = detectionCount,
                        areaConfigured = overlayView.areaPointCount >= 3,
                        customPrompt = claudePromptText(),
                    ),
                )
                appendClaudeDetail(formatClaudeResult(result))
            } catch (e: Exception) {
                appendClaudeDetail("Claude 분석 실패: ${(e.message ?: "알 수 없는 오류").take(120)}")
            } finally {
                claudeInspectionInFlight = false
            }
        }
    }

    private fun offerClaudeInspection(
        jpegBytes: ByteArray?,
        imageMimeType: String,
        detectionCount: Int,
        source: String,
        yoloStatus: String,
    ) {
        if (jpegBytes == null) return
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        if (bitmap != null) {
            ivResultImage.setImageBitmap(bitmap)
        }

        claudePromptInFlight = true
        lastClaudePromptAt = System.currentTimeMillis()
        appendDetailLine("YOLO가 사람을 감지했습니다. 아래 이미지를 확인한 뒤 Claude 검사를 선택하세요.")

        AlertDialog.Builder(this)
            .setTitle("Claude로 검사할까요?")
            .setMessage("아래에 표시된 첫 감지 이미지를 Claude로 정밀 검사합니다.")
            .setPositiveButton("검사") { _, _ ->
                claudePromptInFlight = false
                maybeRunClaudeInspection(
                    jpegBytes = jpegBytes,
                    imageMimeType = imageMimeType,
                    detectionCount = detectionCount,
                    source = source,
                    yoloStatus = yoloStatus,
                )
            }
            .setNegativeButton("건너뛰기") { _, _ ->
                claudePromptInFlight = false
                appendDetailLine("Claude 검사를 건너뛰었습니다.")
            }
            .setOnCancelListener {
                claudePromptInFlight = false
            }
            .show()
    }

    private fun formatClaudeResult(result: ClaudeInspectionResult): String {
        val prefix = "Claude ${result.status}/${result.severity}"
        val recommendation = result.recommendations.firstOrNull()
        val falsePositive = if (result.falsePositiveLikely) " / 오탐 가능성 있음" else ""
        return if (recommendation.isNullOrBlank()) {
            "$prefix: ${result.summary.take(160)}$falsePositive"
        } else {
            "$prefix: ${result.summary.take(140)} / 조치: ${recommendation.take(80)}$falsePositive"
        }
    }

    private fun shouldOfferClaudeInspectionPrompt(serverClaudeJson: JSONObject?): Boolean {
        return com.remotecamera.app.shouldOfferClaudeInspectionPrompt(
            clientClaudeEnabled = isClaudePromptEnabled(),
            claudeApiKey = claudeApiKey(),
            serverClaudeJson = serverClaudeJson,
            promptInFlight = claudePromptInFlight || claudeInspectionInFlight,
            nowMs = System.currentTimeMillis(),
            lastPromptAtMs = lastClaudePromptAt,
            cooldownMs = CLAUDE_INSPECTION_COOLDOWN_MS,
        )
    }

    private fun appendDetailLine(line: String) {
        val current = tvDetail.text?.toString().orEmpty()
        tvDetail.text = if (current.isBlank()) line else "$current\n$line"
    }

    private fun appendClaudeDetail(line: String) {
        latestClaudeDetail = line
        latestClaudeDetailAt = System.currentTimeMillis()
        appendDetailLine(line)
    }

    private fun settingsPrefs() = getSharedPreferences(AppSettings.PREFS_NAME, MODE_PRIVATE)

    private fun normalizedServerUrl() = normalizeServerUrl(
        settingsPrefs().getString(AppSettings.KEY_SERVER_URL, AppSettings.DEFAULT_SERVER_URL).orEmpty()
    )

    private fun serverApiKey() = settingsPrefs().getString(AppSettings.KEY_API_KEY, "").orEmpty()

    private fun claudeApiKey() = settingsPrefs()
        .getString(AppSettings.KEY_CLAUDE_API_KEY, BuildConfig.CLAUDE_API_KEY)
        .orEmpty()
        .trim()

    private fun isClaudePromptEnabled() =
        settingsPrefs().getBoolean(AppSettings.KEY_CLAUDE_PROMPT_ENABLED, false)

    private fun claudePromptText() =
        settingsPrefs().getString(AppSettings.KEY_CLAUDE_PROMPT_TEXT, "").orEmpty()

    private fun updateSettingsSummary() {
        val url = normalizedServerUrl().ifBlank { "서버 URL 없음" }
        val auth = if (serverApiKey().isBlank()) "서버 인증 없음" else "서버 인증 사용"
        val claude = when {
            !isClaudePromptEnabled() -> "Claude 문의 꺼짐"
            claudeApiKey().isBlank() -> "Claude 키 없음"
            else -> "Claude 문의 켜짐"
        }
        tvSettingsSummary.text = "$url\n$auth / $claude"
    }

    private fun buildStreamUrl(serverUrl: String, apiKey: String): String {
        val wsBase = when {
            serverUrl.startsWith("https://") -> "wss://" + serverUrl.removePrefix("https://")
            serverUrl.startsWith("http://") -> "ws://" + serverUrl.removePrefix("http://")
            else -> "wss://$serverUrl"
        }
        val separator = if (wsBase.contains("?")) "&" else "?"
        val keyQuery = if (apiKey.isNotEmpty()) "${separator}key=${Uri.encode(apiKey)}" else ""
        val confidenceQuery = if (keyQuery.isNotEmpty()) {
            "&confidence=$LIVE_CONFIDENCE"
        } else {
            "${separator}confidence=$LIVE_CONFIDENCE"
        }
        val area = overlayView.normalizedAreaJson()
        val areaQuery = if (area != null) {
            val prefix = "&"
            "${prefix}polygon=${Uri.encode(area)}"
        } else {
            ""
        }
        return "$wsBase/stream$keyQuery$confidenceQuery$areaQuery"
    }

    private fun parseDetectionBoxes(json: JSONObject): List<DetectionBox> {
        val frameWidth = json.optInt("frame_width", 0)
        val frameHeight = json.optInt("frame_height", 0)
        val detections = json.optJSONArray("detections") ?: return emptyList()
        val boxes = mutableListOf<DetectionBox>()
        for (i in 0 until detections.length()) {
            val det = detections.optJSONObject(i) ?: continue
            val box = det.optJSONArray("box") ?: continue
            if (box.length() < 4) continue
            boxes.add(
                DetectionBox(
                    x1 = box.optDouble(0).toFloat(),
                    y1 = box.optDouble(1).toFloat(),
                    x2 = box.optDouble(2).toFloat(),
                    y2 = box.optDouble(3).toFloat(),
                    score = det.optDouble("score").toFloat(),
                    inArea = det.optBoolean("in_area"),
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                )
            )
        }
        return boxes
    }

    private fun showResult(text: String, colorRes: Int, detail: String = "") {
        tvResult.text = text
        tvDetail.text = detailWithRecentClaude(detail)
        tvResult.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun detailWithRecentClaude(detail: String): String {
        val claudeDetail = if (
            latestClaudeDetail.isNotBlank() &&
            System.currentTimeMillis() - latestClaudeDetailAt <= CLAUDE_DETAIL_DISPLAY_MS
        ) {
            latestClaudeDetail
        } else {
            ""
        }

        return when {
            detail.isBlank() -> claudeDetail
            claudeDetail.isBlank() -> detail
            detail.contains(claudeDetail) -> detail
            else -> "$detail\n$claudeDetail"
        }
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
        private const val REQ_IMAGE_PICK = 1002
        private const val REQ_REFERENCE_PICK = 1003
        private const val EXTRA_SERVER_URL = "server_url"
        private const val EXTRA_API_KEY = "api_key"
        private const val LIVE_FRAME_INTERVAL_MS = 500L
        private const val LIVE_JPEG_QUALITY = 65
        private const val LIVE_CONFIDENCE = 0.6
        private const val FPS_UPDATE_INTERVAL_MS = 1000L
        private const val CLAUDE_INSPECTION_COOLDOWN_MS = 60_000L
        private const val CLAUDE_DETAIL_DISPLAY_MS = 60_000L
    }
}

data class DetectionBox(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val score: Float,
    val inArea: Boolean,
    val frameWidth: Int,
    val frameHeight: Int,
)

class DetectionOverlayView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onAreaChanged: (() -> Unit)? = null
    val areaPointCount: Int
        get() = areaPoints.size

    private val boxes = mutableListOf<DetectionBox>()
    private val areaPoints = mutableListOf<Pair<Float, Float>>()
    private var frameWidth = 0
    private var frameHeight = 0

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 193, 7)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 193, 7)
        style = Paint.Style.FILL
    }
    private val hintBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 17, 24, 39)
        style = Paint.Style.FILL
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    init {
        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP && width > 0 && height > 0) {
                areaPoints.add(event.x / width.toFloat() to event.y / height.toFloat())
                invalidate()
                onAreaChanged?.invoke()
                true
            } else {
                true
            }
        }
    }

    fun setDetections(newBoxes: List<DetectionBox>, newFrameWidth: Int, newFrameHeight: Int) {
        boxes.clear()
        boxes.addAll(newBoxes)
        frameWidth = newFrameWidth
        frameHeight = newFrameHeight
        invalidate()
    }

    fun clearArea() {
        areaPoints.clear()
        invalidate()
    }

    fun normalizedAreaJson(): String? {
        if (areaPoints.size < 3) return null
        val json = JSONArray()
        areaPoints.forEach { point ->
            json.put(JSONArray().put(point.first.toDouble()).put(point.second.toDouble()))
        }
        return json.toString()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawArea(canvas)
        drawBoxes(canvas)
        drawHint(canvas)
    }

    private fun drawArea(canvas: Canvas) {
        if (areaPoints.isEmpty()) return
        val path = android.graphics.Path()
        areaPoints.forEachIndexed { index, point ->
            val x = point.first * width
            val y = point.second * height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            canvas.drawCircle(x, y, 9f, pointPaint)
        }
        if (areaPoints.size >= 3) path.close()
        canvas.drawPath(path, areaPaint)
    }

    private fun drawBoxes(canvas: Canvas) {
        if (frameWidth <= 0 || frameHeight <= 0) return
        val frame = contentRect()
        boxes.forEach { box ->
            val color = if (box.inArea) Color.RED else Color.rgb(0, 200, 0)
            boxPaint.color = color
            labelBgPaint.color = color
            val left = frame.left + box.x1 / frameWidth * frame.width()
            val top = frame.top + box.y1 / frameHeight * frame.height()
            val right = frame.left + box.x2 / frameWidth * frame.width()
            val bottom = frame.top + box.y2 / frameHeight * frame.height()
            canvas.drawRect(left, top, right, bottom, boxPaint)

            val label = "%.2f".format(Locale.US, box.score)
            val textWidth = labelPaint.measureText(label)
            val labelHeight = 48f
            val labelTop = top.coerceAtLeast(frame.top)
            canvas.drawRect(left, labelTop, left + textWidth + 22f, labelTop + labelHeight, labelBgPaint)
            canvas.drawText(label, left + 10f, labelTop + 36f, labelPaint)
        }
    }

    private fun drawHint(canvas: Canvas) {
        if (areaPoints.isNotEmpty() || boxes.isNotEmpty()) return
        val text = "화면을 터치해 감시 영역 지정"
        val centerX = width / 2f
        val centerY = height - 58f
        val textWidth = hintPaint.measureText(text)
        canvas.drawRoundRect(
            centerX - textWidth / 2f - 22f,
            centerY - 42f,
            centerX + textWidth / 2f + 22f,
            centerY + 16f,
            8f,
            8f,
            hintBgPaint,
        )
        canvas.drawText(text, centerX, centerY, hintPaint)
    }

    private fun contentRect(): RectF {
        if (frameWidth <= 0 || frameHeight <= 0 || width <= 0 || height <= 0) {
            return RectF(0f, 0f, width.toFloat(), height.toFloat())
        }
        val viewRatio = width.toFloat() / height.toFloat()
        val frameRatio = frameWidth.toFloat() / frameHeight.toFloat()
        return if (frameRatio > viewRatio) {
            val contentHeight = width / frameRatio
            val top = (height - contentHeight) / 2f
            RectF(0f, top, width.toFloat(), top + contentHeight)
        } else {
            val contentWidth = height * frameRatio
            val left = (width - contentWidth) / 2f
            RectF(left, 0f, left + contentWidth, height.toFloat())
        }
    }
}

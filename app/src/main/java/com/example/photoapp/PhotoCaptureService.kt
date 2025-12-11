package com.example.photoapp

import android.accessibilityservice.AccessibilityService
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaPlayer
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PhotoCaptureService : AccessibilityService(), LifecycleOwner {

    private lateinit var lifecycleRegistry: LifecycleRegistry

    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var isCameraBound: Boolean = false

    private var faceDetector: FaceDetector? = null

    private var waitingForFace: Boolean = false
    private var lastAnalysisTimeNanos: Long = 0L

    private val client = OkHttpClient()
    private var mediaPlayer: MediaPlayer? = null

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        cameraExecutor = Executors.newSingleThreadExecutor()

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        faceDetector = FaceDetection.getClient(options)

        Log.d(TAG, "onCreate: lifecycle CREATED, face detector initialised")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        // Ensure LED starts off
        LEDUtils.setled(LEDUtils.BLUE, false)

        Log.d(TAG, "onServiceConnected: service connected, camera not started yet")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used in this flow
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                // F2: arm detection and start camera if needed
                KeyEvent.KEYCODE_F2 -> {
                    Log.d(TAG, "onKeyEvent: F2 pressed, arming face detection")
                    waitingForFace = true
                    lastAnalysisTimeNanos = 0L

                    if (!isCameraBound) {
                        Log.d(TAG, "onKeyEvent: camera not bound, calling startCamera()")
                        startCamera()
                    } else {
                        Log.d(TAG, "onKeyEvent: camera already bound, just arming detection")
                    }
                    return true
                }

                // DPAD_DOWN: stop camera and detection to save battery
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    Log.d(TAG, "onKeyEvent: DPAD_DOWN pressed, stopping camera and detection")
                    waitingForFace = false
                    stopCamera()
                    return true
                }
            }
        }
        return false
    }

    private fun startCamera() {
        if (isCameraBound) {
            Log.d(TAG, "startCamera: camera already bound, skipping")
            return
        }

        Log.d(TAG, "startCamera: requesting ProcessCameraProvider")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider
            Log.d(TAG, "startCamera: ProcessCameraProvider obtained")

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(imageProxy)
                    }
                }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    imageAnalysis
                )
                isCameraBound = true

                LEDUtils.setled(LEDUtils.BLUE, true)
                Log.d(TAG, "startCamera: camera bound successfully, isCameraBound=true, LED on")
            } catch (e: Exception) {
                Log.e(TAG, "startCamera: camera binding failed", e)
                isCameraBound = false
                LEDUtils.setled(LEDUtils.BLUE, false)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        if (!isCameraBound) {
            Log.d(TAG, "stopCamera: camera not bound, nothing to do")
            return
        }

        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "stopCamera: error while unbinding camera", e)
        }

        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        isCameraBound = false

        LEDUtils.setled(LEDUtils.BLUE, false)
        Log.d(TAG, "stopCamera: camera stopped, LED off")
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        if (!waitingForFace) {
            imageProxy.close()
            return
        }

        val now = System.nanoTime()
        if (now - lastAnalysisTimeNanos < ONE_SECOND_NS) {
            imageProxy.close()
            return
        }
        lastAnalysisTimeNanos = now

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        Log.d(
            TAG,
            "processImageProxy: running ML Kit on frame, rotation=$rotation, ts=${imageProxy.imageInfo.timestamp}"
        )

        val image = InputImage.fromMediaImage(mediaImage, rotation)

        faceDetector?.process(image)
            ?.addOnSuccessListener { faces ->
                Log.d(TAG, "ML Kit success: detected ${faces.size} faces")
                handleFaceResults(faces, imageProxy)
            }
            ?.addOnFailureListener { e ->
                Log.e(TAG, "Face detection failed", e)
                imageProxy.close()
            }
    }

    private fun handleFaceResults(faces: List<Face>, imageProxy: ImageProxy) {
        if (!waitingForFace) {
            Log.d(TAG, "handleFaceResults: not waiting for face anymore, discarding frame")
            imageProxy.close()
            return
        }

        if (faces.isNotEmpty()) {
            Log.d(TAG, "handleFaceResults: face detected, extracting JPEG and stopping detection")
            waitingForFace = false

            val jpegBytes = convertImageProxyToJpeg(imageProxy)
            imageProxy.close()

            if (jpegBytes != null) {
                Log.d(TAG, "convertImageProxyToJpeg: JPEG size=${jpegBytes.size} bytes")
                playCaptureSoundAndThenUpload(jpegBytes)
            } else {
                Log.e(TAG, "convertImageProxyToJpeg: failed to convert frame")
            }
        } else {
            Log.d(TAG, "handleFaceResults: no faces in this frame")
            imageProxy.close()
        }
    }

    private fun convertImageProxyToJpeg(imageProxy: ImageProxy): ByteArray? {
        val image = imageProxy.image ?: return null

        val width = imageProxy.width
        val height = imageProxy.height

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)

        val chromaRowStride = image.planes[1].rowStride
        val chromaRowPadding = chromaRowStride - width / 2

        var offset = ySize

        if (chromaRowPadding == 0) {
            vBuffer.get(nv21, offset, vSize)
            offset += vSize
            uBuffer.get(nv21, offset, uSize)
        } else {
            for (i in 0 until height / 2) {
                vBuffer.get(nv21, offset, width / 2)
                offset += width / 2
                if (i < height / 2 - 1) {
                    vBuffer.position(vBuffer.position() + chromaRowPadding)
                }
            }
            for (i in 0 until height / 2) {
                uBuffer.get(nv21, offset, width / 2)
                offset += width / 2
                if (i < height / 2 - 1) {
                    uBuffer.position(uBuffer.position() + chromaRowPadding)
                }
            }
        }

        return try {
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
            out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "convertImageProxyToJpeg: JPEG compression failed", e)
            null
        }
    }

    private fun playCaptureSoundAndThenUpload(jpegBytes: ByteArray) {
        stopPlayer()
        try {
            mediaPlayer = MediaPlayer.create(this, R.raw.increment)
            mediaPlayer?.setOnCompletionListener {
                Log.d(TAG, "Capture sound finished, uploading JPEG")
                uploadToServer(jpegBytes)
                stopPlayer()
            }
            mediaPlayer?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play capture sound", e)
            uploadToServer(jpegBytes)
        }
    }

    private fun uploadToServer(jpegBytes: ByteArray) {
        Log.d(TAG, "uploadToServer: sending JPEG to server, bytes=${jpegBytes.size}")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "frame.jpg",
                jpegBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
            )
            .build()

        val request = Request.Builder()
            .url("https://vertically-prime-snake.ngrok-free.app/face/identify-audio")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "uploadToServer: failed ${e.message}", e)
                playErrorSound()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val audioBytes = it.body?.bytes()
                    if (audioBytes != null) {
                        Log.d(TAG, "uploadToServer: received audio, bytes=${audioBytes.size}")
                        playAudioData(audioBytes)
                    } else {
                        Log.e(TAG, "uploadToServer: empty audio response")
                    }
                }
            }
        })
    }

    private fun playAudioData(audioBytes: ByteArray) {
        stopPlayer()
        try {
            val temp = File.createTempFile("result_audio", ".mp3", cacheDir)
            FileOutputStream(temp).use { it.write(audioBytes) }

            mediaPlayer = MediaPlayer()
            mediaPlayer?.setDataSource(temp.absolutePath)
            mediaPlayer?.prepare()
            mediaPlayer?.setOnCompletionListener {
                stopPlayer()
                temp.delete()
            }
            mediaPlayer?.start()
            Log.d(TAG, "playAudioData: playing server audio")
        } catch (e: IOException) {
            Log.e(TAG, "playAudioData: failed", e)
        }
    }

    private fun playErrorSound() {
        stopPlayer()
        try {
            mediaPlayer = MediaPlayer.create(this, R.raw.decrement)
            mediaPlayer?.setOnCompletionListener { stopPlayer() }
            mediaPlayer?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play error sound", e)
        }
    }

    private fun stopPlayer() {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: cleaning up service")
        waitingForFace = false
        stopCamera()
        faceDetector?.close()
        stopPlayer()
        cameraExecutor.shutdown()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    companion object {
        private const val TAG = "PhotoCaptureService"
        private const val ONE_SECOND_NS = 1_000_000_000L
    }
}
package com.example.photoapp

import android.accessibilityservice.AccessibilityService
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
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.graphics.Rect

class PhotoCaptureService : AccessibilityService(), LifecycleOwner {

    private lateinit var lifecycleRegistry: LifecycleRegistry
    private lateinit var cameraExecutor: ExecutorService

    private var imageAnalysis: ImageAnalysis? = null
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

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()

        faceDetector = FaceDetection.getClient(options)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        cameraExecutor = Executors.newSingleThreadExecutor()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        startCamera()
        Log.d(TAG, "PhotoCaptureService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_F2) {
            Log.d(TAG, "F2 pressed → start face detection flow")
            waitingForFace = true
            lastAnalysisTimeNanos = 0L
            return true
        }
        return false
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

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
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    imageAnalysis
                )

                Log.d(TAG, "Camera started for ImageAnalysis mode")

            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        if (!waitingForFace) {
            imageProxy.close()
            return
        }

        val now = System.nanoTime()
        if (now - lastAnalysisTimeNanos < 1_000_000_000L) {
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
        val image = InputImage.fromMediaImage(mediaImage, rotation)

        faceDetector?.process(image)
            ?.addOnSuccessListener { faces ->
                handleFaceResults(faces, imageProxy)
            }
            ?.addOnFailureListener { e ->
                Log.e(TAG, "Face detection failed", e)
                imageProxy.close()
            }
            ?.addOnCompleteListener {}
    }

    private fun handleFaceResults(faces: List<Face>, imageProxy: ImageProxy) {
        if (!waitingForFace) {
            imageProxy.close()
            return
        }

        if (faces.isNotEmpty()) {
            Log.d(TAG, "Face detected → Extracting frame")
            waitingForFace = false

            val jpegBytes = convertImageProxyToJpeg(imageProxy)

            imageProxy.close()

            if (jpegBytes != null) {
                playCaptureSoundAndThenUpload(jpegBytes)
            } else {
                Log.e(TAG, "Failed converting frame to JPEG")
            }
        } else {
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
                if (i < height / 2 - 1) vBuffer.position(vBuffer.position() + chromaRowPadding)
            }
            for (i in 0 until height / 2) {
                uBuffer.get(nv21, offset, width / 2)
                offset += width / 2
                if (i < height / 2 - 1) uBuffer.position(uBuffer.position() + chromaRowPadding)
            }
        }

        return try {
            val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, width, height), 85, out)
            out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "JPEG compression failed", e)
            null
        }
    }

    private fun playCaptureSoundAndThenUpload(jpegBytes: ByteArray) {
        stopPlayer()
        try {
            mediaPlayer = MediaPlayer.create(this, R.raw.increment)
            mediaPlayer?.setOnCompletionListener {
                Log.d(TAG, "Sound finished → uploading JPEG frame")
                uploadToServer(jpegBytes)
                stopPlayer()
            }
            mediaPlayer?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed playing sound", e)
        }
    }

    private fun uploadToServer(jpegBytes: ByteArray) {
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
                Log.e(TAG, "Upload failed: ${e.message}", e)
                playErrorSound()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val audioBytes = it.body?.bytes()
                    if (audioBytes != null) {
                        playAudioData(audioBytes)
                    } else {
                        Log.e(TAG, "Empty audio response")
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
        } catch (e: IOException) {
            Log.e(TAG, "Failed playing server audio", e)
        }
    }

    private fun playErrorSound() {
        stopPlayer()
        try {
            mediaPlayer = MediaPlayer.create(this, R.raw.decrement)
            mediaPlayer?.setOnCompletionListener { stopPlayer() }
            mediaPlayer?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing error sound", e)
        }
    }

    private fun stopPlayer() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        cameraExecutor.shutdown()
        faceDetector?.close()
        stopPlayer()
    }

    companion object {
        private const val TAG = "PhotoCaptureService"
    }
}
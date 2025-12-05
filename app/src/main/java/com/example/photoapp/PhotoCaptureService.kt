package com.example.photoapp

import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PhotoCaptureService : AccessibilityService(), LifecycleOwner {

    private lateinit var lifecycleRegistry: LifecycleRegistry
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var audioManager: AudioManager
    private val client = OkHttpClient()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        cameraExecutor = Executors.newSingleThreadExecutor()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        startCamera()
        Log.d(TAG, "PhotoCaptureService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.w(TAG, "Service has been interrupted by the system.")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_F2) {
            Log.d(TAG, "Camera button (F2) release detected by service. Taking photo.")
            takePhoto()
            return true
        }
        return false
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            imageCapture = ImageCapture.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageCapture)
                Log.d(TAG, "Service camera started and bound to lifecycle.")
            } catch (exc: Exception) {
                Log.e(TAG, "Service camera use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoApp-Images")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Service photo capture failed: ", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val msg = "Service photo capture succeeded: ${output.savedUri}"
                    Log.d(TAG, msg)
                    playCustomSound()
                    output.savedUri?.let { identifyFace(it) }
                }
            }
        )
    }

    private fun identifyFace(fileUri: Uri) {
        Log.d(TAG, "Preparing to identify face for image: $fileUri")
        val imageBytes = try {
            contentResolver.openInputStream(fileUri)?.use { it.readBytes() }
        } catch (e: IOException) {
            Log.e(TAG, "Could not read bytes from content URI for identification", e)
            null
        }

        if (imageBytes == null) {
            Log.e(TAG, "Image bytes are null, aborting identification request.")
            return
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "photo.jpg",
                imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
            )
            .build()

        val request = Request.Builder()
            .url("https://vertically-prime-snake.ngrok-free.app/face/identify")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Face identification request failed: ${e.message}", e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        val identificationResult = it.body?.string()
                        Log.d(TAG, "Face identification successful: $identificationResult")
                    } else {
                        Log.e(TAG, "Face identification request failed with code: ${it.code} and message: ${it.body?.string()}")
                    }
                }
            }
        })
    }

    private fun playCustomSound() {
        try {
            val player = MediaPlayer.create(this, R.raw.increment)
            player.setOnCompletionListener { mp ->
                mp.release()
                Log.d(TAG, "Custom sound finished.")
            }
            player.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play custom sound", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "PhotoCaptureService"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
}

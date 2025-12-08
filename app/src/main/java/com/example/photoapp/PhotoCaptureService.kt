package com.example.photoapp

import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PhotoCaptureService : AccessibilityService(), LifecycleOwner {

    private lateinit var lifecycleRegistry: LifecycleRegistry
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
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
                    playCaptureSound() // 1. Play local sound for immediate feedback
                    output.savedUri?.let { getIdentificationAudio(it) } // 2. Send network request
                }
            }
        )
    }

    private fun playCaptureSound() {
        try {
            val player = MediaPlayer.create(this, R.raw.increment)
            player.setOnCompletionListener { mp ->
                mp.release()
                Log.d(TAG, "Local capture sound finished.")
            }
            player.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play local capture sound", e)
        }
    }

    private fun playErrorSound() {
        try {
            val player = MediaPlayer.create(this, R.raw.decrement)
            player.setOnCompletionListener { mp ->
                mp.release()
                Log.d(TAG, "Local error sound finished.")
            }
            player.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play local error sound", e)
        }
    }

    private fun getIdentificationAudio(fileUri: Uri) {
        Log.d(TAG, "Requesting identification audio for image: $fileUri")
        val imageBytes = try {
            contentResolver.openInputStream(fileUri)?.use { it.readBytes() }
        } catch (e: IOException) {
            Log.e(TAG, "Could not read bytes from content URI", e)
            null
        }

        if (imageBytes == null) {
            Log.e(TAG, "Image bytes are null, aborting audio request.")
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
            .url("https://vertically-prime-snake.ngrok-free.app/face/identify-audio")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Identification audio request failed: ${e.message}", e)
                // Check for the specific no-internet error and play the error sound
                if (e.message?.contains("Unable to resolve host") == true) {
                    playErrorSound()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        val audioBytes = it.body?.bytes()
                        if (audioBytes != null) {
                            Log.d(TAG, "Identification audio received: ${audioBytes.size} bytes.")
                            playAudioData(audioBytes)
                        } else {
                            Log.e(TAG, "Received successful response but audio body was empty.")
                        }
                    } else {
                        Log.e(TAG, "Identification audio request failed with code: ${it.code} and message: ${it.body?.string()}")
                    }
                }
            }
        })
    }

    private fun playAudioData(audioBytes: ByteArray) {
        try {
            val tempMp3 = File.createTempFile("temp_audio", ".mp3", cacheDir)
            val fos = FileOutputStream(tempMp3)
            fos.write(audioBytes)
            fos.close()
            Log.d(TAG, "Audio data saved to temporary file: ${tempMp3.path}")

            val mediaPlayer = MediaPlayer()
            mediaPlayer.setDataSource(tempMp3.absolutePath)
            mediaPlayer.prepare()
            mediaPlayer.setOnCompletionListener { mp ->
                Log.d(TAG, "Finished playing server audio.")
                mp.release()
                tempMp3.delete()
            }
            mediaPlayer.start()
        } catch (e: IOException) {
            Log.e(TAG, "Error playing audio data from server", e)
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

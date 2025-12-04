package com.example.photoapp

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var audioManager: AudioManager

    // Counter variables
    private var counter = 0
    private lateinit var counterText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize AudioManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Initialize counter text view
        counterText = findViewById(R.id.counter_text)
        counterText.text = counter.toString()

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun playNotificationSound() {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        var currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        Log.d(TAG, "Current media volume: $currentVolume / $maxVolume.")

        if (currentVolume == 0) {
            Log.w(TAG, "Media volume was 0. Setting to max to ensure sound is audible.")
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
        }

        try {
            val notificationUri: Uri = Settings.System.DEFAULT_NOTIFICATION_URI
            val player = MediaPlayer.create(this, notificationUri)
            player.setOnCompletionListener { mp ->
                mp.release()
                Log.d(TAG, "MediaPlayer released.")
            }
            player.start()
            Log.d(TAG, "MediaPlayer started.")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to play notification sound", e)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d(TAG, "onKeyDown: Key event received with keyCode: $keyCode")
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                counter++
                counterText.text = counter.toString()
                playNotificationSound()
                Log.d(TAG, "Volume Up pressed. Counter is now: $counter")
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                counter--
                counterText.text = counter.toString()
                playNotificationSound()
                Log.d(TAG, "Volume Down pressed. Counter is now: $counter")
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d(TAG, "onKeyUp: Key event received with keyCode: $keyCode")
        if (keyCode == KeyEvent.KEYCODE_F2) {
            Log.d(TAG, "Camera button (F2) release detected. Attempting to take photo.")
            takePhoto()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun takePhoto() {
        Log.d(TAG, "takePhoto() function was called.")
        val imageCapture = imageCapture
        if (imageCapture == null) {
            Log.e(TAG, "takePhoto: Aborting because imageCapture is null. Is the camera started?")
            return
        }

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoApp-Images")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues)
            .build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults){
                    val msg = "Photo capture succeeded: ${output.savedUri}"
                    Log.d(TAG, msg)
                    playNotificationSound()
                }
            }
        )
    }

    private fun startCamera() {
        Log.d(TAG, "startCamera() called.")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            imageCapture = ImageCapture.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, imageCapture)
                 Log.d(TAG, "Camera started and bound to lifecycle.")
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "PhotoApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}

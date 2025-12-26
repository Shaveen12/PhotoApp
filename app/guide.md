# Building an On-Device Face Recognition App on AISEE

This guide walks through the core architecture and implementation details for building a background-first Android application on the AISEE headset. The example app performs on-device face detection using ML Kit, delegates face recognition to a backend, and communicates results via audio output. All interaction is driven by hardware buttons.

## Platform Overview

AISEE runs a customized version of vanilla Android.

- Runtime permissions are bypassed
- Accessibility services can be enabled programmatically
- Background services run without manual user intervention

This enables applications that behave like embedded systems rather than UI-driven mobile apps.

## Application Structure

The app consists of two core components.

### MainActivity

- Entry point only  
- Launches the background service  
- No UI logic  

// MainActivity.kt
startService(Intent(this, PhotoCaptureService::class.java))
finish()

### PhotoCaptureService

- Runs continuously in the background  
- Owns camera access, ML inference, networking, audio playback, and button handling  
- Contains all functional logic  

## Hardware Buttons

AISEE emits standard Android KeyEvent callbacks for hardware buttons.

Example system log:
KeyEvent { action=ACTION_DOWN, keyCode=KEYCODE_VOLUME_UP }

Key interception:

override fun onKeyEvent(event: KeyEvent): Boolean {
    if (event.action == KeyEvent.ACTION_UP) {
        when (event.keyCode) {
            KeyEvent.KEYCODE_F2 -> {
                // Arm face detection
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Stop camera or reset state
                return true
            }
        }
    }
    return false
}

Each physical button maps to a known Android keycode.

## End-to-End Flow

1. Face embeddings are generated offline and stored on a backend  
2. User presses a hardware key to arm detection  
3. Camera activates and streams frames  
4. ML Kit checks for the presence of a face  
5. First valid frame with a face is selected  
6. JPEG is uploaded to the backend  
7. Backend returns spoken identification audio  
8. Device plays the audio response  
9. Optional enrollment records audio for unknown faces  

## Camera and Vision Pipeline

### ImageAnalysis

CameraX ImageAnalysis is used instead of ImageCapture.

- Low latency  
- Continuous frame stream  
- No shutter interaction  

imageAnalysis = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
    .build()


YUV_420_888 matches the camera sensor output and avoids RGB conversion.

## On-Device Face Detection

Face detection runs fully on-device using ML Kit.

- PERFORMANCE_MODE_FAST  
- No landmark extraction  

Zero-copy pipeline:

val image = InputImage.fromMediaImage(mediaImage, rotation)

faceDetector.process(image).addOnSuccessListener { faces ->
    if (faces.isNotEmpty()) {
        handleFaceResults(faces, imageProxy)
    }
}

Frames without faces are immediately discarded.

## Smart Trigger State Machine

Continuous vision is gated behind a flag.

if (!waitingForFace) {
    imageProxy.close()
    return
}

- Default idle state  
- Activated by hardware key  
- Preserves battery life  

## YUV to JPEG Conversion

ImageAnalysis outputs raw YUV buffers. The backend expects JPEG.

Steps:
1. Rearrange YUV_420_888 planes into NV21  
2. Compress NV21 to JPEG  
3. Upload bytes  

val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
val out = ByteArrayOutputStream()
yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)

No camera session restart is required.

## Audio Playback from Backend

Backend returns a Base64-encoded MP3.

### Decode and Cache

val audioBytes = Base64.decode(base64Audio, Base64.DEFAULT)
val temp = File.createTempFile("server_audio", ".mp3", cacheDir)
FileOutputStream(temp).use { it.write(audioBytes) }

### Playback

mediaPlayer = MediaPlayer().apply {
    setDataSource(temp.absolutePath)
    prepare()
    start()
}

MediaPlayer requires a file-backed source.

## Voice Enrollment with Mic Access

If identification fails, the user speaks the person’s name.

### MediaRecorder Setup

mediaRecorder = MediaRecorder().apply {
    setAudioSource(MediaRecorder.AudioSource.MIC)
    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
    setOutputFile(outputFile.absolutePath)
    prepare()
    start()
}

- AAC in MPEG-4  
- High compression  
- STT-friendly  

Stopping the recorder:

mediaRecorder?.stop()
mediaRecorder?.release()
mediaRecorder = null

## Supporting Utilities

Additional components included in the project:

- VoiceRecorder.kt for mic abstraction  
- ShellOperator.kt for system commands  
- LEDUtils.java for LED feedback  
- FaceOverlayView.kt for debug visualization  

## Summary

AISEE enables service-first Android applications optimized for headless operation.

By combining:

- Hardware button input  
- CameraX ImageAnalysis  
- On-device ML Kit inference  
- Backend recognition  
- Audio-only I/O  

you can build low-latency, privacy-preserving AI assistants without a traditional UI.

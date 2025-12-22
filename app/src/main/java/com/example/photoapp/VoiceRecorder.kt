package com.example.photoapp

import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

class VoiceRecorder(private val outputFile: File) {
    private var mediaRecorder: MediaRecorder? = null
    private val handler = Handler(Looper.getMainLooper())

    fun startRecording(durationMs: Long, onFinished: (File) -> Unit) {
        try {
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            Log.d("VoiceRecorder", "Recording started")

            // Automatically stop after duration
            handler.postDelayed({
                stopRecording()
                onFinished(outputFile)
            }, durationMs)

        } catch (e: Exception) {
            Log.e("VoiceRecorder", "Failed to start recording", e)
            stopRecording() // Cleanup on failure
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {
            // Fails if stop is called immediately after start, ignore safely
        } finally {
            mediaRecorder = null
            Log.d("VoiceRecorder", "Recording stopped and released")
        }
    }
}
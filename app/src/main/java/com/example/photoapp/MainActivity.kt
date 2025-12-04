package com.example.photoapp

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

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
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d(TAG, "onKeyDown: Key event received with keyCode: $keyCode")
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                counter++
                counterText.text = counter.toString()
                Log.d(TAG, "Volume Up pressed. Counter is now: $counter")
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                counter--
                counterText.text = counter.toString()
                Log.d(TAG, "Volume Down pressed. Counter is now: $counter")
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

package com.example.photoapp

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // Counter variables
    private var counter = 0
    private lateinit var counterText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize counter text view
        counterText = findViewById(R.id.counter_text)
        counterText.text = counter.toString()
    }

    private fun playSound(resourceId: Int) {
        try {
            val player = MediaPlayer.create(this, resourceId)
            player.setOnCompletionListener { mp ->
                mp.release()
                Log.d(TAG, "Custom sound MediaPlayer released.")
            }
            player.start()
            Log.d(TAG, "Custom sound MediaPlayer started.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play custom sound", e)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d(TAG, "onKeyDown: Key event received with keyCode: $keyCode")
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                counter++
                counterText.text = counter.toString()
                playSound(R.raw.increment) // Play custom increment sound
                Log.d(TAG, "Volume Up pressed. Counter is now: $counter")
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                counter--
                counterText.text = counter.toString()
                playSound(R.raw.decrement) // Play custom decrement sound
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

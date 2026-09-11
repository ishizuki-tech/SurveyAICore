package com.negi.surveyaicore

internal class NativeLibraryLoadGate(
    private val libraryName: String,
    private val loadLibrary: (String) -> Unit,
) {
    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return

        synchronized(this) {
            if (loaded) return

            loadLibrary(libraryName)
            loaded = true
        }
    }
}

internal object LiteRtNativeLoader {
    private const val LIBRARY_NAME = "litertlm_jni"

    private val gate = NativeLibraryLoadGate(LIBRARY_NAME, System::loadLibrary)

    fun ensureLoaded() {
        gate.ensureLoaded()
    }
}

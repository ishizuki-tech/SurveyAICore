package com.negi.surveyaicore.runtime

internal enum class RuntimeAccelerator(
    val label: String,
) {
    CPU("CPU"),
    GPU("GPU"),
}

internal enum class RuntimeConfigKey {
    /** Engine/KV-cache token capacity; this is not a generation-output limit. */
    MAX_TOKENS,
    TOP_K,
    TOP_P,
    TEMPERATURE,
    ACCELERATOR,
}

internal data class RuntimeModel(
    val name: String,
    val taskPath: String,
    val config: Map<RuntimeConfigKey, Any> = emptyMap(),
) {
    internal fun getPath(): String = taskPath

    internal fun getIntConfigValue(
        key: RuntimeConfigKey,
        default: Int,
    ): Int =
        when (val value = config[key]) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull() ?: default
            else -> default
        }

    internal fun getFloatConfigValue(
        key: RuntimeConfigKey,
        default: Float,
    ): Float =
        when (val value = config[key]) {
            is Number -> value.toFloat()
            is String -> value.trim().toFloatOrNull() ?: default
            else -> default
        }

    internal fun getStringConfigValue(
        key: RuntimeConfigKey,
        default: String,
    ): String =
        when (val value = config[key]) {
            is String -> value
            is RuntimeAccelerator -> value.label
            else -> default
        }
}

internal fun defaultRuntimeModelConfig(): MutableMap<RuntimeConfigKey, Any> =
    mutableMapOf(
        RuntimeConfigKey.ACCELERATOR to RuntimeAccelerator.GPU.label,
        RuntimeConfigKey.MAX_TOKENS to 512,
        RuntimeConfigKey.TOP_K to 1,
        RuntimeConfigKey.TOP_P to 0.0f,
        RuntimeConfigKey.TEMPERATURE to 0.0f,
    )

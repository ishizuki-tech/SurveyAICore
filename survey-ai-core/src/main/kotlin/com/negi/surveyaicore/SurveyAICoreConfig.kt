package com.negi.surveyaicore

import com.negi.surveyaicore.runtime.RuntimeConfigKey

enum class SurveyAICoreAccelerator {
    CPU,
    GPU,
}

data class SurveyAICoreConfig(
    val accelerator: SurveyAICoreAccelerator = SurveyAICoreAccelerator.GPU,
    val maxTokens: Int = 512,
    val topK: Int = 1,
    val topP: Float = 0.0f,
    val temperature: Float = 0.0f,
)

internal fun SurveyAICoreConfig.toRuntimeConfig(): Map<RuntimeConfigKey, Any> =
    mapOf(
        RuntimeConfigKey.ACCELERATOR to accelerator.name,
        RuntimeConfigKey.MAX_TOKENS to maxTokens,
        RuntimeConfigKey.TOP_K to topK,
        RuntimeConfigKey.TOP_P to topP,
        RuntimeConfigKey.TEMPERATURE to temperature,
    )

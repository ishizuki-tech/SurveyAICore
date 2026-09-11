package com.negi.surveyaicore

import com.negi.surveyaicore.runtime.RuntimeConfigKey
import org.junit.Assert.assertEquals
import org.junit.Test

class SurveyAICoreConfigTest {
    @Test
    fun defaultsMatchVerifiedRuntimeDefaults() {
        val config = SurveyAICoreConfig()

        assertEquals(SurveyAICoreAccelerator.GPU, config.accelerator)
        assertEquals(512, config.maxTokens)
        assertEquals(1, config.topK)
        assertEquals(0.0f, config.topP)
        assertEquals(0.0f, config.temperature)
    }

    @Test
    fun runtimeConfigPreservesPublicValuesUsingRuntimeKeys() {
        val config = SurveyAICoreConfig(
            accelerator = SurveyAICoreAccelerator.CPU,
            maxTokens = 768,
            topK = 7,
            topP = 0.25f,
            temperature = 1.5f,
        )

        assertEquals(
            mapOf(
                RuntimeConfigKey.ACCELERATOR to "CPU",
                RuntimeConfigKey.MAX_TOKENS to 768,
                RuntimeConfigKey.TOP_K to 7,
                RuntimeConfigKey.TOP_P to 0.25f,
                RuntimeConfigKey.TEMPERATURE to 1.5f,
            ),
            config.toRuntimeConfig(),
        )
    }
}

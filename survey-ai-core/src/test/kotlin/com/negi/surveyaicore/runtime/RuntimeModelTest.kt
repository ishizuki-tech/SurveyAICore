package com.negi.surveyaicore.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeModelTest {
    @Test
    fun defaultConfigMatchesVerifiedRuntimeValues() {
        val config = defaultRuntimeModelConfig()

        assertEquals(RuntimeAccelerator.GPU.label, config[RuntimeConfigKey.ACCELERATOR])
        assertEquals(512, config[RuntimeConfigKey.MAX_TOKENS])
        assertEquals(1, config[RuntimeConfigKey.TOP_K])
        assertEquals(0.0f, config[RuntimeConfigKey.TOP_P])
        assertEquals(0.0f, config[RuntimeConfigKey.TEMPERATURE])
    }

    @Test
    fun intConfigAcceptsNumbersAndTrimmedStrings() {
        val numberModel = RuntimeModel(
            name = "model",
            taskPath = "/model.task",
            config = mapOf(RuntimeConfigKey.MAX_TOKENS to 512.9),
        )
        val stringModel = RuntimeModel(
            name = "model",
            taskPath = "/model.task",
            config = mapOf(RuntimeConfigKey.TOP_K to " 7 "),
        )

        assertEquals(512, numberModel.getIntConfigValue(RuntimeConfigKey.MAX_TOKENS, 1))
        assertEquals(7, stringModel.getIntConfigValue(RuntimeConfigKey.TOP_K, 1))
    }

    @Test
    fun intConfigUsesDefaultForInvalidOrMissingValues() {
        val invalidModel = RuntimeModel(
            name = "model",
            taskPath = "/model.task",
            config = mapOf(RuntimeConfigKey.MAX_TOKENS to "not-a-number"),
        )
        val missingModel = RuntimeModel(name = "model", taskPath = "/model.task")

        assertEquals(99, invalidModel.getIntConfigValue(RuntimeConfigKey.MAX_TOKENS, 99))
        assertEquals(99, missingModel.getIntConfigValue(RuntimeConfigKey.MAX_TOKENS, 99))
    }

    @Test
    fun floatConfigAcceptsNumbersAndTrimmedStrings() {
        val numberModel = RuntimeModel(
            name = "model",
            taskPath = "/model.task",
            config = mapOf(RuntimeConfigKey.TOP_P to 0.25),
        )
        val stringModel = RuntimeModel(
            name = "model",
            taskPath = "/model.task",
            config = mapOf(RuntimeConfigKey.TEMPERATURE to " 1.5 "),
        )

        assertEquals(0.25f, numberModel.getFloatConfigValue(RuntimeConfigKey.TOP_P, 0f))
        assertEquals(1.5f, stringModel.getFloatConfigValue(RuntimeConfigKey.TEMPERATURE, 0f))
    }

    @Test
    fun floatConfigUsesDefaultForInvalidOrMissingValues() {
        val invalidModel = RuntimeModel(
            name = "model",
            taskPath = "/model.task",
            config = mapOf(RuntimeConfigKey.TOP_P to "not-a-number"),
        )
        val missingModel = RuntimeModel(name = "model", taskPath = "/model.task")

        assertEquals(0.9f, invalidModel.getFloatConfigValue(RuntimeConfigKey.TOP_P, 0.9f))
        assertEquals(0.9f, missingModel.getFloatConfigValue(RuntimeConfigKey.TOP_P, 0.9f))
    }

    @Test
    fun stringConfigAcceptsStringsAndRuntimeAccelerators() {
        val stringModel = RuntimeModel(
            name = "model",
            taskPath = "/model.task",
            config = mapOf(RuntimeConfigKey.ACCELERATOR to "CPU"),
        )
        val acceleratorModel = RuntimeModel(
            name = "model",
            taskPath = "/model.task",
            config = mapOf(RuntimeConfigKey.ACCELERATOR to RuntimeAccelerator.GPU),
        )

        assertEquals("CPU", stringModel.getStringConfigValue(RuntimeConfigKey.ACCELERATOR, "GPU"))
        assertEquals("GPU", acceleratorModel.getStringConfigValue(RuntimeConfigKey.ACCELERATOR, "CPU"))
    }

    @Test
    fun stringConfigUsesDefaultForUnsupportedOrMissingValues() {
        val unsupportedModel = RuntimeModel(
            name = "model",
            taskPath = "/model.task",
            config = mapOf(RuntimeConfigKey.ACCELERATOR to 1),
        )
        val missingModel = RuntimeModel(name = "model", taskPath = "/model.task")

        assertEquals("GPU", unsupportedModel.getStringConfigValue(RuntimeConfigKey.ACCELERATOR, "GPU"))
        assertEquals("GPU", missingModel.getStringConfigValue(RuntimeConfigKey.ACCELERATOR, "GPU"))
    }
}

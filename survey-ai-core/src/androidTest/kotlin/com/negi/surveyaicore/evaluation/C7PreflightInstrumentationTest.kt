package com.negi.surveyaicore.evaluation

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.surveyaicore.SurveyAICore
import com.negi.surveyaicore.SurveyAICoreAccelerator
import com.negi.surveyaicore.SurveyAICoreConfig
import com.negi.surveyaicore.SurveyAICoreResult
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class C7PreflightInstrumentationTest {
    @Test
    fun coreOwnedPreflight(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        assertEquals(C7_TEST_PACKAGE, testContext.packageName)

        val modelFile = File(testContext.filesDir, MODEL_FILE_NAME)
        assertTrue("C7 model is missing at ${modelFile.path}", modelFile.isFile)
        assertEquals(EXPECTED_MODEL_SIZE, modelFile.length())

        Log.i(
            TAG,
            "C7_PRECHECK package=${testContext.packageName} modelPath=${modelFile.path} " +
                "size=${modelFile.length()} sha256VerifiedByRunner=true " +
                "requestedAccelerator=GPU maxTokens=512 topK=1 topP=0.0 temperature=0.0",
        )

        var core: SurveyAICore? = null
        var primaryFailure: Throwable? = null
        try {
            val config =
                SurveyAICoreConfig(
                    accelerator = SurveyAICoreAccelerator.GPU,
                    maxTokens = 512,
                    topK = 1,
                    topP = 0.0f,
                    temperature = 0.0f,
                )
            core = withTimeout(PREFLIGHT_TIMEOUT_MS) {
                SurveyAICore.create(testContext, modelFile, config)
            }

            // These direct references prove normal Android instrumentation test access to Core internals.
            val evaluator = AnswerEvaluator(SurveyAICoreEvaluationInferencePort(core))
            val generator = FollowupGenerator(SurveyAICoreFollowupInferencePort(core))
            val orchestrator =
                FollowupOrchestrator(
                    evaluator = evaluator,
                    generator = generator,
                    policy = FollowupOrchestrationPolicy(maxFollowups = 1),
                )
            assertNotNull(evaluator)
            assertNotNull(generator)
            assertNotNull(orchestrator)

            val result =
                withTimeout(PREFLIGHT_TIMEOUT_MS) {
                    core.generate(PROMPT)
                }
            Log.i(
                TAG,
                "C7_GENERATE durationMs=${result.durationMs} timedOut=${result.timedOut} " +
                    "error=${result.errorMessage ?: "NONE"} rawOutput=${result.text}",
            )
            assertFalse("C7 preflight generation timed out", result.timedOut)
            assertNull("C7 preflight generation failed: ${result.errorMessage}", result.errorMessage)
            assertTrue("C7 preflight generation output is blank", result.text.isNotBlank())
        } catch (failure: Throwable) {
            primaryFailure = failure
            Log.e(TAG, "C7_PREFLIGHT_FAILURE", failure)
            throw failure
        } finally {
            core?.let { createdCore ->
                withContext(NonCancellable) {
                    try {
                        createdCore.close()
                        Log.i(TAG, "C7_CLOSE_SUCCESS")
                    } catch (cleanupFailure: Throwable) {
                        Log.e(TAG, "C7_CLOSE_FAILURE", cleanupFailure)
                        primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
                    }
                }
            }
        }
    }

    @Test
    fun evaluatorCharacterization(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        assertEquals(C7_TEST_PACKAGE, testContext.packageName)

        val modelFile = File(testContext.filesDir, MODEL_FILE_NAME)
        assertTrue("C7 model is missing at ${modelFile.path}", modelFile.isFile)
        assertEquals(EXPECTED_MODEL_SIZE, modelFile.length())
        Log.i(
            TAG,
            "C7_EVAL_PRECHECK package=${testContext.packageName} modelPath=${modelFile.path} " +
                "size=${modelFile.length()} sha256VerifiedByRunner=true " +
                "requestedAccelerator=GPU maxTokens=512 topK=1 topP=0.0 temperature=0.0",
        )

        var core: SurveyAICore? = null
        var primaryFailure: Throwable? = null
        try {
            core =
                withTimeout(PREFLIGHT_TIMEOUT_MS) {
                    SurveyAICore.create(testContext, modelFile, gpuConfig())
                }
            val port = RecordingEvaluationPort(core)
            val evaluator = AnswerEvaluator(port)

            EVALUATOR_CASES.forEach { case ->
                runEvaluatorCase(evaluator, port, case)
            }
            assertEquals("C7 evaluator characterization must use exactly four inferences", 4, port.calls)
            Log.i(TAG, "C7_EVAL_SUITE_PASS cases=${EVALUATOR_CASES.size} inferences=${port.calls}")
        } catch (failure: Throwable) {
            primaryFailure = failure
            Log.e(TAG, "C7_EVAL_SUITE_FAIL", failure)
            throw failure
        } finally {
            core?.let { createdCore ->
                withContext(NonCancellable) {
                    try {
                        createdCore.close()
                        Log.i(TAG, "C7_CLOSE_SUCCESS")
                    } catch (cleanupFailure: Throwable) {
                        Log.e(TAG, "C7_CLOSE_FAILURE", cleanupFailure)
                        primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
                    }
                }
            }
        }
    }

    @Test
    fun generatorCharacterization(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        assertEquals(C7_TEST_PACKAGE, testContext.packageName)

        val modelFile = File(testContext.filesDir, MODEL_FILE_NAME)
        assertTrue("C7 model is missing at ${modelFile.path}", modelFile.isFile)
        assertEquals(EXPECTED_MODEL_SIZE, modelFile.length())
        Log.i(
            TAG,
            "C7_GEN_PRECHECK package=${testContext.packageName} modelPath=${modelFile.path} " +
                "size=${modelFile.length()} sha256VerifiedByRunner=true " +
                "requestedAccelerator=GPU maxTokens=512 topK=1 topP=0.0 temperature=0.0",
        )

        var core: SurveyAICore? = null
        var primaryFailure: Throwable? = null
        try {
            core =
                withTimeout(PREFLIGHT_TIMEOUT_MS) {
                    SurveyAICore.create(testContext, modelFile, gpuConfig())
                }
            val port = RecordingFollowupPort(core)
            val generator = FollowupGenerator(port)

            GENERATOR_CASES.forEach { case ->
                runGeneratorCase(generator, port, case)
            }
            assertEquals("C7 generator characterization must use exactly four inferences", 4, port.calls)
            Log.i(TAG, "C7_GEN_SUITE_PASS cases=${GENERATOR_CASES.size} inferences=${port.calls}")
        } catch (failure: Throwable) {
            primaryFailure = failure
            Log.e(TAG, "C7_GEN_SUITE_FAIL", failure)
            throw failure
        } finally {
            core?.let { createdCore ->
                withContext(NonCancellable) {
                    try {
                        createdCore.close()
                        Log.i(TAG, "C7_CLOSE_SUCCESS")
                    } catch (cleanupFailure: Throwable) {
                        Log.e(TAG, "C7_CLOSE_FAILURE", cleanupFailure)
                        primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
                    }
                }
            }
        }
    }

    @Test
    fun orchestrationCharacterization(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        assertEquals(C7_TEST_PACKAGE, testContext.packageName)

        val modelFile = File(testContext.filesDir, MODEL_FILE_NAME)
        assertTrue("C7 model is missing at ${modelFile.path}", modelFile.isFile)
        assertEquals(EXPECTED_MODEL_SIZE, modelFile.length())
        Log.i(
            TAG,
            "C7_ORCH_PRECHECK package=${testContext.packageName} modelPath=${modelFile.path} " +
                "size=${modelFile.length()} sha256VerifiedByRunner=true " +
                "requestedAccelerator=GPU maxTokens=512 topK=1 topP=0.0 temperature=0.0",
        )

        var core: SurveyAICore? = null
        var primaryFailure: Throwable? = null
        try {
            core = withTimeout(PREFLIGHT_TIMEOUT_MS) { SurveyAICore.create(testContext, modelFile, gpuConfig()) }
            val evaluationPort = RecordingEvaluationPort(core)
            val generationPort = RecordingFollowupPort(core)
            val orchestrator =
                FollowupOrchestrator(
                    evaluator = AnswerEvaluator(evaluationPort),
                    generator = FollowupGenerator(generationPort),
                    policy = FollowupOrchestrationPolicy(maxFollowups = 1),
                )
            val initialState =
                FollowupSessionState(
                    question = ORCHESTRATION_QUESTION,
                    expectedAnswerTarget = ORCHESTRATION_EXPECTED_TARGET,
                    originalAnswer = ORCHESTRATION_ORIGINAL_ANSWER,
                )

            val first = orchestrator.advance(initialState)
            assertEquals("first step must make one evaluator inference", 1, evaluationPort.calls)
            assertEquals("first step must make one generator inference", 1, generationPort.calls)
            logOrchestrationEvaluation("FIRST", evaluationPort, 0)
            logOrchestrationGeneration("FOLLOWUP", generationPort, 0)
            val needFollowup = first as? FollowupStepOutcome.NeedFollowup
                ?: throw AssertionError("first step must be NeedFollowup, was $first")
            Log.i(TAG, "C7_ORCH_FIRST_OUTCOME outcome=NeedFollowup question=${needFollowup.question}")

            val second =
                orchestrator.advance(
                    initialState.copy(
                        answeredFollowups =
                            listOf(
                                AnsweredFollowup(
                                    question = needFollowup.question,
                                    answer = ORCHESTRATION_FOLLOWUP_ANSWER,
                                ),
                            ),
                    ),
                )
            assertEquals("second step must make one fresh evaluator inference", 2, evaluationPort.calls)
            assertEquals("second step must not make a second generator inference", 1, generationPort.calls)
            assertTrue(
                "second evaluator prompt must include the fixed follow-up answer",
                evaluationPort.prompts[1].contains(ORCHESTRATION_FOLLOWUP_ANSWER),
            )
            logOrchestrationEvaluation("SECOND", evaluationPort, 1)
            when (second) {
                is FollowupStepOutcome.Completed ->
                    Log.i(
                        TAG,
                        "C7_ORCH_FINAL outcome=Completed score=${second.evaluation.score} " +
                            "missingPoints=${second.evaluation.missingPoints}",
                    )
                is FollowupStepOutcome.Stopped -> {
                    assertEquals(StopReason.FollowupCapacityExhausted, second.reason)
                    Log.i(
                        TAG,
                        "C7_ORCH_FINAL outcome=Stopped(FollowupCapacityExhausted) " +
                            "score=${second.evaluation.score} missingPoints=${second.evaluation.missingPoints}",
                    )
                }
                else -> throw AssertionError("second step must be Completed or capacity Stopped, was $second")
            }
            assertEquals("C7 orchestration must use exactly three model inferences", 3, evaluationPort.calls + generationPort.calls)
            Log.i(
                TAG,
                "C7_ORCH_SUITE_PASS evaluatorInferences=${evaluationPort.calls} " +
                    "generatorInferences=${generationPort.calls} totalInferences=${evaluationPort.calls + generationPort.calls}",
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            Log.e(TAG, "C7_ORCH_SUITE_FAIL", failure)
            throw failure
        } finally {
            core?.let { createdCore ->
                withContext(NonCancellable) {
                    try {
                        createdCore.close()
                        Log.i(TAG, "C7_CLOSE_SUCCESS")
                    } catch (cleanupFailure: Throwable) {
                        Log.e(TAG, "C7_CLOSE_FAILURE", cleanupFailure)
                        primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
                    }
                }
            }
        }
    }

    private fun logOrchestrationEvaluation(
        step: String,
        port: RecordingEvaluationPort,
        index: Int,
    ) {
        val result = port.results[index]
        Log.i(
            TAG,
            "C7_ORCH_EVAL_RAW step=$step durationMs=${result.durationMs} timedOut=${result.timedOut} " +
                "error=${result.errorMessage ?: "NONE"} rawOutput=${result.text}",
        )
        assertFalse("$step evaluator inference timed out", result.timedOut)
        assertNull("$step evaluator inference failed: ${result.errorMessage}", result.errorMessage)
        val parsed = StrictEvaluationParser.parse(result.text).getOrElse { failure ->
            throw AssertionError("$step evaluator output was rejected by StrictEvaluationParser", failure)
        }
        Log.i(TAG, "C7_ORCH_EVAL_NORMALIZED step=$step score=${parsed.score} missingPoints=${parsed.missingPoints}")
    }

    private fun logOrchestrationGeneration(
        step: String,
        port: RecordingFollowupPort,
        index: Int,
    ) {
        val result = port.results[index]
        Log.i(
            TAG,
            "C7_ORCH_GEN_RAW step=$step durationMs=${result.durationMs} timedOut=${result.timedOut} " +
                "error=${result.errorMessage ?: "NONE"} rawOutput=${result.text}",
        )
        assertFalse("$step generator inference timed out", result.timedOut)
        assertNull("$step generator inference failed: ${result.errorMessage}", result.errorMessage)
        val question = FollowupResultValidator.validate(result.text).getOrElse { failure ->
            throw AssertionError("$step generator output was rejected by FollowupResultValidator", failure)
        }
        Log.i(TAG, "C7_ORCH_GEN_NORMALIZED step=$step question=$question")
    }

    private suspend fun runEvaluatorCase(
        evaluator: AnswerEvaluator,
        port: RecordingEvaluationPort,
        case: EvaluatorCase,
    ) {
        val input =
            EvaluationInput(
                question = EVALUATOR_QUESTION,
                expectedAnswerTarget = EVALUATOR_EXPECTED_TARGET,
                originalAnswer = case.originalAnswer,
            )
        val callsBefore = port.calls
        Log.i(
            TAG,
            "C7_EVAL_CASE_START id=${case.id} name=${case.name} originalAnswer=${case.originalAnswer}",
        )
        try {
            val outcome = evaluator.evaluate(input)
            assertEquals("${case.id} must make exactly one inference", callsBefore + 1, port.calls)

            val result = requireNotNull(port.lastResult) { "${case.id} produced no Core result" }
            Log.i(
                TAG,
                "C7_EVAL_RAW_OUTPUT id=${case.id} prompt=${port.lastPrompt} " +
                    "durationMs=${result.durationMs} timedOut=${result.timedOut} " +
                    "error=${result.errorMessage ?: "NONE"} rawOutput=${result.text}",
            )
            assertFalse("${case.id} evaluator inference timed out", result.timedOut)
            assertNull("${case.id} evaluator inference failed: ${result.errorMessage}", result.errorMessage)
            val parsed =
                StrictEvaluationParser.parse(result.text).getOrElse { parseFailure ->
                    throw AssertionError("${case.id} raw output was rejected by StrictEvaluationParser", parseFailure)
                }
            val normalized = outcome.normalizedName()
            Log.i(
                TAG,
                "C7_EVAL_NORMALIZED id=${case.id} score=${parsed.score} " +
                    "missingPoints=${parsed.missingPoints} outcome=$normalized",
            )
            assertTrue("${case.id} must return a non-failure evaluator outcome", outcome !is EvaluationOutcome.Failure)
            assertTrue("${case.id} expected ${case.expectedOutcome}", outcome::class.java == case.expectedOutcome)
            Log.i(TAG, "C7_EVAL_CASE_PASS id=${case.id} outcome=$normalized")
        } catch (failure: Throwable) {
            val result = port.lastResult
            Log.e(
                TAG,
                "C7_EVAL_CASE_FAIL id=${case.id} calls=${port.calls - callsBefore} " +
                    "durationMs=${result?.durationMs} timedOut=${result?.timedOut} " +
                    "error=${result?.errorMessage ?: failure.message ?: failure::class.java.simpleName} " +
                    "rawOutput=${result?.text}",
                failure,
            )
            throw failure
        }
    }

    private fun EvaluationOutcome.normalizedName(): String =
        when (this) {
            is EvaluationOutcome.Complete -> "Complete"
            is EvaluationOutcome.Incomplete -> "Incomplete"
            is EvaluationOutcome.Failure -> "Failure(${category::class.simpleName})"
        }

    private suspend fun runGeneratorCase(
        generator: FollowupGenerator,
        port: RecordingFollowupPort,
        case: GeneratorCase,
    ) {
        val input =
            FollowupGenerationInput(
                question = case.question,
                expectedAnswerTarget = case.expectedAnswerTarget,
                originalAnswer = case.originalAnswer,
                missingPoints = case.missingPoints,
            )
        val callsBefore = port.calls
        Log.i(
            TAG,
            "C7_GEN_CASE_START id=${case.id} name=${case.name} question=${case.question} " +
                "expectedAnswerTarget=${case.expectedAnswerTarget} originalAnswer=${case.originalAnswer} " +
                "missingPoints=${case.missingPoints}",
        )
        try {
            val outcome = generator.generate(input)
            assertEquals("${case.id} must make exactly one inference", callsBefore + 1, port.calls)

            val result = requireNotNull(port.lastResult) { "${case.id} produced no Core result" }
            Log.i(
                TAG,
                "C7_GEN_RAW_OUTPUT id=${case.id} prompt=${port.lastPrompt} " +
                    "durationMs=${result.durationMs} timedOut=${result.timedOut} " +
                    "error=${result.errorMessage ?: "NONE"} rawOutput=${result.text}",
            )
            assertFalse("${case.id} generator inference timed out", result.timedOut)
            assertNull("${case.id} generator inference failed: ${result.errorMessage}", result.errorMessage)
            val validated =
                FollowupResultValidator.validate(result.text).getOrElse { validationFailure ->
                    throw AssertionError("${case.id} raw output was rejected by FollowupResultValidator", validationFailure)
                }
            val success = outcome as? FollowupGenerationOutcome.Success
                ?: throw AssertionError("${case.id} returned $outcome")
            assertEquals("${case.id} normalized output must match validator output", validated, success.question)

            val terminalMark = success.question.last()
            val codePointCount = success.question.codePointCount(0, success.question.length)
            assertTrue("${case.id} output must be one line", '\n' !in success.question && '\r' !in success.question)
            assertTrue("${case.id} output must end in a supported question mark", terminalMark in TERMINAL_QUESTION_MARKS)
            assertEquals(
                "${case.id} output must contain exactly one supported question mark",
                1,
                success.question.count { it in TERMINAL_QUESTION_MARKS },
            )
            assertTrue("${case.id} output must be at most 280 Unicode code points", codePointCount <= MAX_QUESTION_CODE_POINTS)
            Log.i(
                TAG,
                "C7_GEN_NORMALIZED id=${case.id} question=${success.question} " +
                    "terminalPunctuation=$terminalMark codePointCount=$codePointCount outcome=Success",
            )
            Log.i(TAG, "C7_GEN_CASE_PASS id=${case.id} outcome=Success")
        } catch (failure: Throwable) {
            val result = port.lastResult
            Log.e(
                TAG,
                "C7_GEN_CASE_FAIL id=${case.id} calls=${port.calls - callsBefore} " +
                    "durationMs=${result?.durationMs} timedOut=${result?.timedOut} " +
                    "error=${result?.errorMessage ?: failure.message ?: failure::class.java.simpleName} " +
                    "rawOutput=${result?.text}",
                failure,
            )
            throw failure
        }
    }

    private fun gpuConfig() =
        SurveyAICoreConfig(
            accelerator = SurveyAICoreAccelerator.GPU,
            maxTokens = 512,
            topK = 1,
            topP = 0.0f,
            temperature = 0.0f,
        )

    private class RecordingEvaluationPort(
        private val core: SurveyAICore,
    ) : EvaluationInferencePort {
        var calls = 0
            private set
        var lastPrompt = ""
            private set
        var lastResult: SurveyAICoreResult? = null
            private set
        val prompts = mutableListOf<String>()
        val results = mutableListOf<SurveyAICoreResult>()

        override suspend fun generate(prompt: String): EvaluationInferenceResult {
            calls++
            lastPrompt = prompt
            prompts += prompt
            val result = withTimeout(PREFLIGHT_TIMEOUT_MS) { core.generate(prompt) }
            lastResult = result
            results += result
            return when {
                result.timedOut -> EvaluationInferenceResult.Timeout
                result.errorMessage != null -> EvaluationInferenceResult.Failure
                else -> EvaluationInferenceResult.Success(result.text)
            }
        }
    }

    private class RecordingFollowupPort(
        private val core: SurveyAICore,
    ) : FollowupInferencePort {
        var calls = 0
            private set
        var lastPrompt = ""
            private set
        var lastResult: SurveyAICoreResult? = null
            private set
        val prompts = mutableListOf<String>()
        val results = mutableListOf<SurveyAICoreResult>()

        override suspend fun generate(prompt: String): FollowupInferenceResult {
            calls++
            lastPrompt = prompt
            prompts += prompt
            val result = withTimeout(PREFLIGHT_TIMEOUT_MS) { core.generate(prompt) }
            lastResult = result
            results += result
            return when {
                result.timedOut -> FollowupInferenceResult.Timeout
                result.errorMessage != null -> FollowupInferenceResult.Failure
                else -> FollowupInferenceResult.Success(result.text)
            }
        }
    }

    private data class EvaluatorCase(
        val id: String,
        val name: String,
        val originalAnswer: String,
        val expectedOutcome: Class<out EvaluationOutcome>,
    )

    private data class GeneratorCase(
        val id: String,
        val name: String,
        val question: String,
        val expectedAnswerTarget: String,
        val originalAnswer: String,
        val missingPoints: List<String>,
    )

    private companion object {
        const val TAG = "SurveyAICoreC7"
        const val C7_TEST_PACKAGE = "com.negi.surveyaicore.test"
        const val MODEL_FILE_NAME = "c7-model.litertlm"
        const val EXPECTED_MODEL_SIZE = 4_919_541_760L
        const val PREFLIGHT_TIMEOUT_MS = 180_000L
        const val PROMPT = "Answer in one short sentence: What is the capital of Japan?"
        const val EVALUATOR_QUESTION =
            "How did the drought affect your maize harvest, and what did you do in response?"
        const val EVALUATOR_EXPECTED_TARGET = "State the harvest impact and the response taken."
        const val ORCHESTRATION_QUESTION =
            "How did the drought affect your maize harvest, and what did you do in response?"
        const val ORCHESTRATION_EXPECTED_TARGET = "State the harvest impact and the response taken."
        const val ORCHESTRATION_ORIGINAL_ANSWER = "Drought cut my maize harvest by half."
        const val ORCHESTRATION_FOLLOWUP_ANSWER = "I used mulching and drought-tolerant seed."
        const val MAX_QUESTION_CODE_POINTS = 280
        val TERMINAL_QUESTION_MARKS = setOf('?', '？', '؟')
        val EVALUATOR_CASES =
            listOf(
                EvaluatorCase(
                    id = "CASE_1_COMPLETE",
                    name = "complete",
                    originalAnswer =
                        "Drought cut my maize harvest by about half, so I used mulching and drought-tolerant seed.",
                    expectedOutcome = EvaluationOutcome.Complete::class.java,
                ),
                EvaluatorCase(
                    id = "CASE_2_INCOMPLETE",
                    name = "incomplete",
                    originalAnswer = "Drought reduced my maize harvest.",
                    expectedOutcome = EvaluationOutcome.Incomplete::class.java,
                ),
                EvaluatorCase(
                    id = "CASE_3_BLANK",
                    name = "blank",
                    originalAnswer = "",
                    expectedOutcome = EvaluationOutcome.Incomplete::class.java,
                ),
                EvaluatorCase(
                    id = "CASE_4_PARTIAL_LOW_RELEVANCE",
                    name = "partial_low_relevance",
                    originalAnswer = "I planted maize in April.",
                    expectedOutcome = EvaluationOutcome.Incomplete::class.java,
                ),
            )
        val GENERATOR_CASES =
            listOf(
                GeneratorCase(
                    id = "CASE_1_ONE_MISSING_POINT",
                    name = "one_missing_point",
                    question = EVALUATOR_QUESTION,
                    expectedAnswerTarget = EVALUATOR_EXPECTED_TARGET,
                    originalAnswer = "Drought reduced my maize harvest.",
                    missingPoints = listOf("Response taken"),
                ),
                GeneratorCase(
                    id = "CASE_2_TWO_RELATED_MISSING_POINTS",
                    name = "two_related_missing_points",
                    question = "How did the drought affect your maize harvest?",
                    expectedAnswerTarget = "State the cause and approximate amount of harvest loss.",
                    originalAnswer = "My maize harvest was lower.",
                    missingPoints = listOf("Harvest loss amount", "Cause of harvest loss"),
                ),
                GeneratorCase(
                    id = "CASE_3_JAPANESE",
                    name = "japanese",
                    question = "干ばつでトウモロコシの収穫はどう変わり、どのように対応しましたか？",
                    expectedAnswerTarget = "収穫への影響と、その対応方法を答える。",
                    originalAnswer = "収穫量が減りました。",
                    missingPoints = listOf("どのように対応したか"),
                ),
                GeneratorCase(
                    id = "CASE_4_ARABIC",
                    name = "arabic",
                    question = "كيف أثّر الجفاف في محصول الذرة لديك، وما الإجراء الذي اتخذته استجابةً لذلك؟",
                    expectedAnswerTarget = "اذكر أثر الجفاف على المحصول والإجراء الذي اتخذته.",
                    originalAnswer = "انخفض محصول الذرة بسبب الجفاف.",
                    missingPoints = listOf("الإجراء الذي تم اتخاذه"),
                ),
            )
    }
}

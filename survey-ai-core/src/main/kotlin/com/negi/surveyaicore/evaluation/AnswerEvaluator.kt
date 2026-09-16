package com.negi.surveyaicore.evaluation

import kotlinx.coroutines.CancellationException

internal class AnswerEvaluator(
    private val inferencePort: EvaluationInferencePort,
    private val promptBuilder: EvaluationPromptBuilder = EvaluationPromptBuilder,
    private val parser: StrictEvaluationParser = StrictEvaluationParser,
) {
    suspend fun evaluate(
        input: EvaluationInput,
        policyConfig: EvaluationPolicyConfig = EvaluationPolicyConfig(),
    ): EvaluationOutcome {
        val prompt = promptBuilder.build(input)
        val inferenceResult =
            try {
                inferencePort.generate(prompt)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                return EvaluationOutcome.Failure(EvaluationFailureCategory.InferenceError)
            }

        val rawOutput =
            when (inferenceResult) {
                is EvaluationInferenceResult.Success -> inferenceResult.rawOutput
                EvaluationInferenceResult.Timeout -> {
                    return EvaluationOutcome.Failure(EvaluationFailureCategory.Timeout)
                }
                EvaluationInferenceResult.Failure -> {
                    return EvaluationOutcome.Failure(EvaluationFailureCategory.InferenceError)
                }
            }

        val evaluation =
            parser.parse(rawOutput).getOrElse {
                return EvaluationOutcome.Failure(EvaluationFailureCategory.InvalidModelOutput)
            }
        return EvaluationPolicy(policyConfig).apply(evaluation)
    }
}

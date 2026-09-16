package com.negi.surveyaicore.evaluation

import kotlinx.coroutines.CancellationException

internal class FollowupGenerator(
    private val inferencePort: FollowupInferencePort,
    private val promptBuilder: FollowupPromptBuilder = FollowupPromptBuilder,
    private val resultValidator: FollowupResultValidator = FollowupResultValidator,
) {
    suspend fun generate(input: FollowupGenerationInput): FollowupGenerationOutcome {
        val prompt = promptBuilder.build(input)
        val inferenceResult =
            try {
                inferencePort.generate(prompt)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                return FollowupGenerationOutcome.Failure(FollowupGenerationFailureCategory.InferenceError)
            }

        val rawOutput =
            when (inferenceResult) {
                is FollowupInferenceResult.Success -> inferenceResult.rawOutput
                FollowupInferenceResult.Timeout -> {
                    return FollowupGenerationOutcome.Failure(FollowupGenerationFailureCategory.Timeout)
                }
                FollowupInferenceResult.Failure -> {
                    return FollowupGenerationOutcome.Failure(FollowupGenerationFailureCategory.InferenceError)
                }
            }

        val question =
            resultValidator.validate(rawOutput).getOrElse {
                return FollowupGenerationOutcome.Failure(FollowupGenerationFailureCategory.InvalidModelOutput)
            }
        return FollowupGenerationOutcome.Success(question)
    }
}

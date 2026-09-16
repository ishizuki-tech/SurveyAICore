package com.negi.surveyaicore.evaluation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal object StrictEvaluationParser {
    private val json = Json

    fun parse(rawOutput: String): Result<AnswerEvaluation> =
        runCatching {
            val trimmed = rawOutput.trim()
            require(trimmed.isNotBlank()) { "Evaluation output is blank" }

            val jsonObject = json.parseToJsonElement(trimmed) as? JsonObject
                ?: error("Evaluation output must be exactly one JSON object")

            val score = jsonObject.requiredScore()
            val missingPoints = jsonObject.requiredMissingPoints()
            AnswerEvaluation(score = score, missingPoints = missingPoints)
        }

    private fun JsonObject.requiredScore(): Int {
        val primitive = this["score"] as? JsonPrimitive
            ?: error("Evaluation output is missing score")
        return primitive
            .takeUnless { it.isString }
            ?.intOrNull
            ?.takeIf { it in 1..100 }
            ?: error("score must be an unquoted integer from 1 to 100")
    }

    private fun JsonObject.requiredMissingPoints(): List<String> {
        val values = this["missing_points"] as? JsonArray
            ?: error("Evaluation output is missing missing_points")
        return values.mapIndexed { index, element ->
            val primitive = element as? JsonPrimitive
                ?: error("missing_points[$index] must be a nonblank string")
            require(primitive.isString && primitive.content.isNotBlank()) {
                "missing_points[$index] must be a nonblank string"
            }
            primitive.content
        }
    }
}

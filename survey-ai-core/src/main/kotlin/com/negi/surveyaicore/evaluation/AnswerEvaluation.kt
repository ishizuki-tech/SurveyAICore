package com.negi.surveyaicore.evaluation

internal data class AnswerEvaluation(
    val score: Int,
    val missingPoints: List<String>,
)

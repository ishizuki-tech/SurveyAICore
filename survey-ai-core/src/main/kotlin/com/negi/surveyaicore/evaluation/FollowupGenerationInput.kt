package com.negi.surveyaicore.evaluation

internal data class FollowupGenerationInput(
    val question: String,
    val expectedAnswerTarget: String,
    val originalAnswer: String,
    val answeredFollowups: List<AnsweredFollowup> = emptyList(),
    val missingPoints: List<String>,
) {
    init {
        require(question.isNotBlank()) { "question must not be blank" }
        require(expectedAnswerTarget.isNotBlank()) { "expectedAnswerTarget must not be blank" }
        require(missingPoints.isNotEmpty()) { "missingPoints must not be empty" }
        require(missingPoints.all { it.isNotBlank() }) { "missingPoints must contain only nonblank values" }
    }
}

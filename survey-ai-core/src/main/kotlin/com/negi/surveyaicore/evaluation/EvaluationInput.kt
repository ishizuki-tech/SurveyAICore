package com.negi.surveyaicore.evaluation

internal data class EvaluationInput(
    val question: String,
    val expectedAnswerTarget: String,
    val originalAnswer: String,
    val answeredFollowups: List<AnsweredFollowup> = emptyList(),
) {
    init {
        require(question.isNotBlank()) { "question must not be blank" }
        require(expectedAnswerTarget.isNotBlank()) { "expectedAnswerTarget must not be blank" }
    }
}

internal data class AnsweredFollowup(
    val question: String,
    val answer: String,
) {
    init {
        require(question.isNotBlank()) { "follow-up question must not be blank" }
        require(answer.isNotBlank()) { "follow-up answer must not be blank" }
    }
}

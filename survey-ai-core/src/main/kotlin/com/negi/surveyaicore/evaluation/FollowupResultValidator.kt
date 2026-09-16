package com.negi.surveyaicore.evaluation

internal object FollowupResultValidator {
    private const val MAX_CODE_POINTS = 280

    private val terminalQuestionMarks = setOf('?', '？', '؟')

    private val explanatoryPrefixes =
        listOf(
            "here is a follow-up question:",
            "here is the follow-up question:",
            "sure:",
            "follow-up:",
            "follow up:",
            "follow-up question:",
            "question:",
        )

    fun validate(rawOutput: String): Result<String> =
        runCatching {
            val question = rawOutput.trim()
            require(question.isNotBlank()) { "Follow-up output is blank" }
            require("```" !in question) { "Follow-up output must not contain Markdown fences" }
            require(!question.startsWith("{") && !question.startsWith("[")) {
                "Follow-up output must not be JSON"
            }
            require('\n' !in question && '\r' !in question) {
                "Follow-up output must be one line"
            }
            require(explanatoryPrefixes.none { question.startsWith(it, ignoreCase = true) }) {
                "Follow-up output must not contain an explanatory prefix"
            }
            require(question.last() in terminalQuestionMarks && question.count { it in terminalQuestionMarks } == 1) {
                "Follow-up output must be exactly one question"
            }
            require(question.codePointCount(0, question.length) <= MAX_CODE_POINTS) {
                "Follow-up output is excessively long"
            }
            question
        }
}

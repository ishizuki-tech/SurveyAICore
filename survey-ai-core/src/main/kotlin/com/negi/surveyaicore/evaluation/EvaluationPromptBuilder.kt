package com.negi.surveyaicore.evaluation

internal object EvaluationPromptBuilder {
    private const val SYSTEM_INSTRUCTIONS =
        "Evaluate whether the respondent's supplied answers satisfy the survey question and expected answer target.\n" +
            "Return only compact JSON {\"score\":N,\"missing_points\":[]}. " +
            "score must be an unquoted integer from 1 to 100.\n" +
            "Assess semantic relevance and completeness using the original answer and all answered follow-ups together. " +
            "Do not infer facts the respondent did not state. High scores require strong semantic completeness against the question and expected target. " +
            "A concise answer may be complete; a verbose answer may still be incomplete.\n" +
            "missing_points must list only specific unresolved information required by the question or expected target, using their established language. " +
            "Do not generate a follow-up question. Do not use markdown. Return exactly one JSON object."

    fun build(input: EvaluationInput): String =
        buildString {
            append(SYSTEM_INSTRUCTIONS)
            append("\n\nINPUT:\n")
            appendField("Question", input.question)
            appendField("Expected answer target", input.expectedAnswerTarget)
            appendField("Original answer", input.originalAnswer)
            append("Answered follow-ups:")
            if (input.answeredFollowups.isEmpty()) {
                append(" none\n")
            } else {
                append('\n')
                input.answeredFollowups.forEachIndexed { index, followup ->
                    append("- Follow-up ")
                    append(index + 1)
                    append(" question: ")
                    append(renderUserContent(followup.question))
                    append("\n  Follow-up ")
                    append(index + 1)
                    append(" answer: ")
                    append(renderUserContent(followup.answer))
                    append('\n')
                }
            }
        }.trimEnd()

    private fun StringBuilder.appendField(label: String, value: String) {
        append(label)
        append(": ")
        append(renderUserContent(value))
        append('\n')
    }

    private fun renderUserContent(value: String): String =
        normalizeNewlines(value)
            .trim()
            .replace("<start_of_turn>user", "< start_of_turn >user")
            .replace("<start_of_turn>model", "< start_of_turn >model")
            .replace("<end_of_turn>", "< end_of_turn >")

    private fun normalizeNewlines(value: String): String =
        value.replace("\r\n", "\n").replace('\r', '\n')
}

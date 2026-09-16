package com.negi.surveyaicore.evaluation

internal object FollowupPromptBuilder {
    private const val SYSTEM_INSTRUCTIONS =
        "Generate exactly one short follow-up question that obtains unresolved information from Missing information.\n" +
            "Target the most important unresolved point, or naturally combine closely related points only when one clear question remains. " +
            "Do not answer the survey question or explain your reasoning. Do not output JSON, Markdown, code fences, labels, prefixes, or multiple questions. " +
            "Return only the concise follow-up question, using the language established by the survey question, expected target, and supplied context."

    fun build(input: FollowupGenerationInput): String =
        buildString {
            append(SYSTEM_INSTRUCTIONS)
            append("\n\nINPUT:\n")
            appendField("Survey question", input.question)
            appendField("Expected answer target", input.expectedAnswerTarget)
            appendField("Original answer", input.originalAnswer)
            append("Previous answered follow-ups:")
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
            append("Missing information:\n")
            input.missingPoints.forEach { missingPoint ->
                append("- ")
                append(renderUserContent(missingPoint))
                append('\n')
            }
        }.trimEnd()

    private fun StringBuilder.appendField(label: String, value: String) {
        append(label)
        append(": ")
        append(renderUserContent(value))
        append('\n')
    }

    private fun renderUserContent(value: String): String =
        value.replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
            .replace("<start_of_turn>user", "< start_of_turn >user")
            .replace("<start_of_turn>model", "< start_of_turn >model")
            .replace("<end_of_turn>", "< end_of_turn >")
}

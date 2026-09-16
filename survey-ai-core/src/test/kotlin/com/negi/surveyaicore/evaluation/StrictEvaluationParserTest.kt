package com.negi.surveyaicore.evaluation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictEvaluationParserTest {
    @Test
    fun parsesScoreNinetyWithNoMissingPoints() {
        assertEquals(AnswerEvaluation(90, emptyList()), StrictEvaluationParser.parse("  {\"score\":90,\"missing_points\":[]}  ").getOrThrow())
    }

    @Test
    fun parsesScoreFiftyWithMissingPoints() {
        assertEquals(
            AnswerEvaluation(50, listOf("Cause of the change")),
            StrictEvaluationParser.parse("{\"score\":50,\"missing_points\":[\"Cause of the change\"]}").getOrThrow(),
        )
    }

    @Test
    fun ignoresUnknownExtraFields() {
        val result = StrictEvaluationParser.parse("{\"score\":90,\"missing_points\":[],\"future_field\":true}")

        assertEquals(AnswerEvaluation(90, emptyList()), result.getOrThrow())
    }

    @Test
    fun rejectsInvalidOutputs() {
        listOf(
            "",
            "{not json}",
            "[]",
            "{\"missing_points\":[]}",
            "{\"score\":90}",
            "{\"score\":\"90\",\"missing_points\":[]}",
            "{\"score\":90.0,\"missing_points\":[]}",
            "{\"score\":0,\"missing_points\":[]}",
            "{\"score\":101,\"missing_points\":[]}",
            "{\"score\":90,\"missing_points\":\"none\"}",
            "{\"score\":null,\"missing_points\":[]}",
            "{\"score\":90,\"missing_points\":null}",
            "{\"score\":90,\"missing_points\":[null]}",
            "{\"score\":90,\"missing_points\":[\"  \"]}",
            "```json\n{\"score\":90,\"missing_points\":[]}\n```",
            "Result: {\"score\":90,\"missing_points\":[]}",
            "{\"score\":90,\"missing_points\":[]} done",
        ).forEach { rawOutput ->
            assertTrue("Expected rejection for: $rawOutput", StrictEvaluationParser.parse(rawOutput).isFailure)
        }
    }
}

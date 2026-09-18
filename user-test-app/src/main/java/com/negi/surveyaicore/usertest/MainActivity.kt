package com.negi.surveyaicore.usertest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.negi.surveyaicore.SurveyAIFollowupOutcome
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val factory =
                remember(applicationContext) {
                    UserTestViewModelFactory(
                        executor = PublicCoreUserTestExecutor(applicationContext),
                        modelFile = File(filesDir, USER_TEST_MODEL_FILE_NAME),
                    )
                }
            val viewModel: UserTestViewModel = viewModel(factory = factory)
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    UserTestScreen(viewModel)
                }
            }
        }
    }
}

@Composable
private fun UserTestScreen(viewModel: UserTestViewModel) {
    val state by viewModel.state.collectAsState()
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val sourceFieldsEnabled = !state.busy && state.outcome !is SurveyAIFollowupOutcome.NeedFollowup
        Text("CoreAI User Test", style = MaterialTheme.typography.headlineSmall)
        Text("Status: ${state.status}")
        Text("Model: app-private $USER_TEST_MODEL_FILE_NAME (provision with the companion host script)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::initialize, enabled = !state.busy && !state.initialized) {
                Text("Initialize")
            }
            Button(onClick = viewModel::close, enabled = !state.busy && state.initialized) {
                Text("Close")
            }
        }

        OutlinedTextField(
            value = state.question,
            onValueChange = viewModel::updateQuestion,
            modifier = Modifier.fillMaxWidth(),
            enabled = sourceFieldsEnabled,
            label = { Text("Question") },
        )
        OutlinedTextField(
            value = state.expectedAnswerTarget,
            onValueChange = viewModel::updateExpectedAnswerTarget,
            modifier = Modifier.fillMaxWidth(),
            enabled = sourceFieldsEnabled,
            label = { Text("Expected answer target") },
        )
        OutlinedTextField(
            value = state.originalAnswer,
            onValueChange = viewModel::updateOriginalAnswer,
            modifier = Modifier.fillMaxWidth(),
            enabled = sourceFieldsEnabled,
            label = { Text("Original answer") },
        )
        Text("Policy: score threshold 90 · allowed missing points 0 · max follow-ups 1")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::evaluate, enabled = state.initialized && !state.busy) {
                Text("Evaluate")
            }
            Button(onClick = viewModel::newTest, enabled = !state.busy) {
                Text("New Test")
            }
        }

        PublicOutcome(state = state, viewModel = viewModel)
        state.lastAdvanceMs?.let { Text("Last advance: ${it} ms") }

        if (state.events.isNotEmpty()) {
            Text("In-memory public event log", style = MaterialTheme.typography.titleMedium)
            state.events.forEach { event ->
                Text("• ${event.message}${event.elapsedMs?.let { " (${it} ms)" } ?: ""}")
            }
        }
    }
}

@Composable
private fun PublicOutcome(
    state: UserTestUiState,
    viewModel: UserTestViewModel,
) {
    when (val outcome = state.outcome) {
        null -> Unit
        SurveyAIFollowupOutcome.Completed -> Text("Completed", style = MaterialTheme.typography.titleMedium)
        is SurveyAIFollowupOutcome.NeedFollowup -> {
            Text("Follow-up: ${outcome.question}", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.followupAnswer,
                onValueChange = viewModel::updateFollowupAnswer,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
                label = { Text("Follow-up answer") },
            )
            Button(onClick = viewModel::submitFollowup, enabled = !state.busy) {
                Text("Submit Follow-up")
            }
        }
        is SurveyAIFollowupOutcome.Stopped -> Text("Stopped: ${outcome.reason.name}", style = MaterialTheme.typography.titleMedium)
        is SurveyAIFollowupOutcome.Failure ->
            Text("Failure: ${outcome.stage.name} / ${outcome.category.name}", style = MaterialTheme.typography.titleMedium)
    }
}

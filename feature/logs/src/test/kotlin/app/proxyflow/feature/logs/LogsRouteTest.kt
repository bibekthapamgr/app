package app.proxyflow.feature.logs

import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.model.ConnectionStage
import app.proxyflow.core.model.LogEntry
import app.proxyflow.core.model.LogEvent
import app.proxyflow.core.testing.FakeLogRepository
import app.proxyflow.core.testing.MainDispatcherRule
import app.proxyflow.core.testing.TestProfiles
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Logs screen in an activity, as the app shows it: what a configuration change keeps and what leaving closes. */
@RunWith(AndroidJUnit4::class)
class LogsRouteTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val entry = LogEntry(
        id = 1,
        timeMillis = 1_000,
        level = LogEntry.Level.Error,
        event = LogEvent.ConnectFailed,
        stage = ConnectionStage.ProxyHandshake,
        code = "AUTH_FAILED",
    )
    private lateinit var viewModel: LogsViewModel

    @Test
    fun aRotationKeepsTheDetailSheetAndTheClearConfirmationOpenAndLeavingClosesThem() {
        // Made here, once the rule has set the main dispatcher the ViewModel's scope runs on.
        viewModel = LogsViewModel(FakeLogRepository(listOf(entry)), TestProfiles().repository, work = EmptyCoroutineContext)
        val watching = CoroutineScope(main.dispatcher)
        watching.launch { viewModel.uiState.collect() }
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { show(it) }
        viewModel.select(entry)
        viewModel.requestClear()

        // Turned, or the dark theme or another language: the activity and the screen are built again, the ViewModel kept.
        scenario.recreate()
        scenario.onActivity { show(it) }
        assertEquals("the detail sheet stays open", entry.id, viewModel.uiState.value.selected?.id)
        assertTrue("the Clear confirmation stays open", viewModel.uiState.value.confirmingClear)

        scenario.close() // left
        assertNull(viewModel.uiState.value.selected)
        assertFalse(viewModel.uiState.value.confirmingClear)
        watching.cancel()
    }

    private fun show(activity: ComponentActivity) = activity.setContentView(
        ComposeView(activity).apply {
            setContent { LogsRoute(viewModel, "1.0") }
        },
    )
}

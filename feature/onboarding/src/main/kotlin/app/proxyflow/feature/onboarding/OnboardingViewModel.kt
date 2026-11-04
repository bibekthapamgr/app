package app.proxyflow.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.proxyflow.core.data.SettingsRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The first-launch pages (docs/m1/03 J1): once the user has gone through them, they are not shown again. Nothing here
 * contacts the network.
 */
class OnboardingViewModel(private val settings: SettingsRepository) : ViewModel() {
    private val done = MutableStateFlow(false)

    /** True once the pages were gone through and that is saved: the app moves on to Home. */
    val finished: StateFlow<Boolean> = done.asStateFlow()

    /**
     * The last page was left. Saved first, so a process that ends right after never shows the pages again; a settings
     * file that can't be written still lets the user in, and the pages come back at the next start.
     */
    fun finish() {
        viewModelScope.launch {
            try {
                settings.setOnboardingCompleted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Shown again next time; the app is usable now.
            }
            done.value = true
        }
    }
}

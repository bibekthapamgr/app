package app.proxyflow

import android.util.Log

/** Throwaway: proves the LogSecret Lint check runs in :app:lintDebug (never merged). */
internal object LintProbe {
    fun probe(password: String) {
        Log.d("LintProbe", "value $password")
    }
}

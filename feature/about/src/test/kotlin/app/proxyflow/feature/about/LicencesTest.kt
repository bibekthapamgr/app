package app.proxyflow.feature.about

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The open-source notices (M9): what the app ships lists the engine's modules and the app's libraries, each under a
 * licence whose full text is shipped too; the screens show them all and open each text.
 */
@RunWith(AndroidJUnit4::class)
class LicencesTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val sample = OpenSourceNotices(
        licences = listOf(
            OpenSourceLicence("Apache-2.0", "Apache License 2.0", "licences/Apache-2.0.txt"),
            OpenSourceLicence("BSD-3-Clause-Go", "BSD 3-Clause License (The Go Authors)", "licences/BSD-3-Clause-Go.txt"),
        ),
        libraries = listOf(
            OpenSourceLibrary("androidx.core:core", "1.17.0", "Apache-2.0"),
            OpenSourceLibrary("org.jetbrains.kotlin:kotlin-stdlib", "2.2.21", "Apache-2.0"),
            OpenSourceLibrary("Go standard library and runtime", "go1.27.3", "BSD-3-Clause-Go"),
        ),
    )

    @Test
    fun theShippedNoticesListTheEngineAndTheAppsLibrariesWithEveryText() {
        val read = OpenSourceNotices.read(context)
        assertNotNull("the notices are part of the app", read)
        val notices = read!!
        val names = notices.libraries.map { it.name }
        assertTrue("the engine's netstack", "gvisor.dev/gvisor" in names)
        assertTrue("the engine's runtime", "Go standard library and runtime" in names)
        assertTrue(
            "the app's own libraries",
            names.any { it.startsWith("androidx.") } && names.any { it.startsWith("org.jetbrains.kotlin") },
        )
        val ids = notices.licences.map { it.id }.toSet()
        for (library in notices.libraries) assertTrue("${library.name}: ${library.licence}", library.licence in ids)
        for (licence in notices.licences) {
            val text = OpenSourceNotices.text(context, licence)
            assertNotNull(licence.id, text)
            assertTrue(licence.id, text!!.length > 1_000)
        }
    }

    @Test
    fun aLicenceFlowsToTheScreenWithItsWordsUnchanged() {
        val text = "Copyright 2009 The Go Authors.\n\nRedistribution and use in source and binary forms, with or without\n" +
            "modification, are permitted provided that the following conditions are\nmet:\n\n   * Redistributions of source code must"
        assertEquals(
            listOf(
                "Copyright 2009 The Go Authors.",
                "Redistribution and use in source and binary forms, with or without modification, are permitted provided that " +
                    "the following conditions are met:",
                "* Redistributions of source code must",
            ),
            paragraphsOf(text),
        )
    }

    /** The BSD licences' three conditions are a list: each stays a paragraph of its own. */
    @Test
    fun aLicencesListItemsAreParagraphsOfTheirOwn() {
        val text = "met:\n\n   * Redistributions of source code must retain the above copyright\nnotice, this list of conditions.\n" +
            "   * Redistributions in binary form must reproduce the above\ncopyright notice.\n" +
            "   * Neither the name of Google LLC nor the names of its\ncontributors may be used.\n\nTHIS SOFTWARE"
        assertEquals(
            listOf(
                "met:",
                "* Redistributions of source code must retain the above copyright notice, this list of conditions.",
                "* Redistributions in binary form must reproduce the above copyright notice.",
                "* Neither the name of Google LLC nor the names of its contributors may be used.",
                "THIS SOFTWARE",
            ),
            paragraphsOf(text),
        )
    }

    @Test
    fun eachLicenceListsItsLibrariesAndOpensItsText() {
        val opened = mutableListOf<String>()
        compose.setContent { ProxyFlowTheme { LicencesScreen(sample, onBack = {}, onOpenLicence = { opened += it }) } }

        compose.onNodeWithText("Apache License 2.0").assertIsDisplayed()
        compose.onNodeWithText("2 libraries").assertIsDisplayed()
        compose.onNodeWithText("androidx.core:core").assertIsDisplayed()
        compose.onNodeWithText("Read the Apache License 2.0").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Read the BSD 3-Clause License (The Go Authors)"))
        compose.onNodeWithText("1 library").assertIsDisplayed()
        compose.onNodeWithText("Read the BSD 3-Clause License (The Go Authors)").performClick()
        assertEquals(listOf("Apache-2.0", "BSD-3-Clause-Go"), opened)
    }

    @Test
    fun aLicenceTextIsShownAndAMissingOneSaysSo() {
        var text: String? by mutableStateOf("Copyright 2009 The Go Authors.")
        compose.setContent {
            ProxyFlowTheme { LicenceTextScreen(title = "BSD 3-Clause License (The Go Authors)", text = text, onBack = {}) }
        }
        compose.onNodeWithText("Copyright 2009 The Go Authors.").assertIsDisplayed()
        text = null
        compose.onNodeWithText("Couldn't open the licences").assertIsDisplayed()
    }

    @Test
    fun noticesThatCantBeReadSaySoInsteadOfAnEmptyList() {
        compose.setContent { ProxyFlowTheme { LicencesScreen(null, onBack = {}, onOpenLicence = {}) } }
        compose.onNodeWithText("Couldn't open the licences").assertIsDisplayed()
    }
}

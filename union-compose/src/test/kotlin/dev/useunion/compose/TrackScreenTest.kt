package dev.useunion.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackScreenTest {
    @get:Rule val rule = createComposeRule()

    private val seen = ArrayList<Pair<String, String?>>()

    @Test fun firesOnceOnEntryAndNotOnRecompositionWithTheSameArguments() {
        var counter by mutableStateOf(0)
        rule.setContent {
            @Suppress("UNUSED_EXPRESSION") counter // recompose whenever the counter changes
            TrackScreenWith("Checkout", "checkout") { n, f -> seen.add(n to f) }
        }
        rule.waitForIdle()
        assertEquals(listOf("Checkout" to "checkout"), seen)
        repeat(3) { counter++; rule.waitForIdle() }
        assertEquals("recomposition is not a new view", 1, seen.size)
    }

    @Test fun firesAgainWhenTheNameChangesAndWhenTheComposableLeavesAndReenters() {
        var name by mutableStateOf("Home")
        var visible by mutableStateOf(true)
        rule.setContent {
            if (visible) TrackScreenWith(name, null) { n, f -> seen.add(n to f) }
        }
        rule.waitForIdle()
        name = "Settings"
        rule.waitForIdle()
        visible = false
        rule.waitForIdle()
        visible = true
        rule.waitForIdle()
        assertEquals(listOf("Home" to null, "Settings" to null, "Settings" to null), seen)
    }
}

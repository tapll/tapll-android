package dev.useunion.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.useunion.Union

/**
 * Fires one `$screen_view` each time this composable **enters the composition**, and not on every
 * recomposition. Place it at the top of a screen's content:
 *
 * ```kotlin
 * @Composable fun CheckoutScreen() {
 *     TrackScreen("Checkout", feature = "checkout")   // the feature's discovery step
 *     ...
 * }
 * ```
 *
 * It re-fires when [name] or [feature] change (a different screen in the same slot is a different
 * view) and when the composable leaves and re-enters, such as navigating away and back. A rotation
 * that recreates the activity re-enters the composition and so fires again; the pipeline keeps the
 * session, only the view count moves. With [feature], the view is that feature's discovery step.
 */
@Composable
public fun TrackScreen(name: String, feature: String? = null) {
    TrackScreenWith(name, feature) { n, f -> Union.screen(n, feature = f) }
}

/** The composable with its sink injected, so the "once per entry" behaviour is testable without a configured SDK. */
@Composable
internal fun TrackScreenWith(name: String, feature: String?, report: (name: String, feature: String?) -> Unit) {
    // Keyed on the arguments, so recomposition with the same values is not a new view; `rememberUpdatedState`
    // is not needed because the lambda is only read once, when the effect starts.
    LaunchedEffect(name, feature) {
        report(name, feature)
    }
}

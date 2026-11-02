package com.tapll.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.tapll.internal.crash.CrashReporter

/**
 * Bridges `ProcessLifecycleOwner` into the pipeline: the app is "foreground" while any activity is
 * started, and `ON_STOP` arrives ~700 ms after the last one stops (so a rotation is not a background).
 * Must be created on the main thread.
 */
internal class AppLifecycleObserver(
    private val client: Client,
    private val crash: CrashReporter?,
) : DefaultLifecycleObserver {
    private val lifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle

    /**
     * `addObserver` replays `ON_START` when the process is already started (configure called from an
     * Activity, or late). On iOS `willEnterForeground` never fires on a cold start, so the replay must
     * not read as a `$foreground` right after `$session_start`.
     */
    private var skipNextStart = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    fun register() {
        lifecycle.addObserver(this)
    }

    fun unregister() {
        lifecycle.removeObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        crash?.setForeground(true)
        if (skipNextStart) {
            skipNextStart = false
            return
        }
        client.post { it.pipeline.willEnterForeground() }
    }

    override fun onStop(owner: LifecycleOwner) {
        crash?.setForeground(false)
        client.launch {
            client.barrier()
            client.pipeline.didEnterBackground()
        }
    }
}

/**
 * Opt-in automatic `$screen_view`: the activity's simple name on every `onResume`. A rotation (the
 * same class recreated after `isChangingConfigurations`) is the same screen and is not reported twice.
 * Compose and fragment-based apps with a single activity should use `TrackScreen` / `Tapll.screen`.
 */
internal class ScreenTracking(private val client: Client) : Application.ActivityLifecycleCallbacks {
    private var recreating: Class<*>? = null

    override fun onActivityResumed(activity: Activity) {
        val cls = activity.javaClass
        if (recreating == cls) {
            recreating = null
            return
        }
        val name = screenName(cls) ?: return
        client.post { it.pipeline.screen(name, emptyMap()) }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity.isChangingConfigurations) recreating = activity.javaClass
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    companion object {
        /** Anonymous classes have no simple name and are not screens. */
        fun screenName(cls: Class<*>): String? = cls.simpleName.takeIf { it.isNotEmpty() }?.take(Limits.SCREEN_NAME_MAX)
    }
}

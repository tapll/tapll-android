package dev.useunion.internal

/** Detects first open / update from the last seen app version + build. */
internal object InstallState {
    private const val VERSION_KEY = "last_app_version"
    private const val BUILD_KEY = "last_app_build"

    sealed interface Outcome {
        /** Android has nothing that survives uninstall, so there is no `reinstall` flag: a reinstall is a new install. */
        data object FirstOpen : Outcome
        data class Updated(val previousVersion: String, val previousBuild: String) : Outcome
        data object Unchanged : Outcome
    }

    fun evaluate(store: KeyValueStore, device: DeviceContext): Outcome {
        val pv = store.string(VERSION_KEY)
        val pb = store.string(BUILD_KEY)
        store.set(VERSION_KEY, device.appVersion)
        store.set(BUILD_KEY, device.appBuild)
        if (pv == null || pb == null) return Outcome.FirstOpen
        if (pv != device.appVersion || pb != device.appBuild) return Outcome.Updated(pv, pb)
        return Outcome.Unchanged
    }
}

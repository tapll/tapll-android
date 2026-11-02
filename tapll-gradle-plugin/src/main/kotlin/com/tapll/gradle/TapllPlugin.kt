package com.tapll.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `id("com.tapll")`, applied to an **application** module.
 *
 * For every release variant that is minified it
 *  1. generates a lowercase UUIDv4 build id and merges it into the app as `assets/tapll-build-id.txt`
 *     (the SDK reads it into `report.build_id`), and
 *  2. registers `uploadTapllMapping<Variant>`, finalizing `minify<Variant>WithR8`, which POSTs the gzipped
 *     `mapping.txt` to Tapll under that build id.
 *
 * Debug variants are untouched: no R8, no mapping, nothing to join. Nothing here can fail a build.
 *
 * This class deliberately names no Android Gradle Plugin type: Gradle introspects a plugin class when it
 * instantiates it, and one that mentions AGP classes would fail to load on a project without AGP instead of
 * warning. The AGP wiring lives in [AndroidWiring], which is only loaded once AGP is present.
 */
class TapllPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("tapll", TapllExtension::class.java)

        project.pluginManager.withPlugin("com.android.application") {
            AndroidWiring.wire(project, extension)
        }

        project.afterEvaluate {
            if (!project.plugins.hasPlugin("com.android.application")) {
                project.logger.warn("Tapll: com.tapll needs the com.android.application plugin; nothing was configured for ${project.path}.")
            }
        }
    }
}

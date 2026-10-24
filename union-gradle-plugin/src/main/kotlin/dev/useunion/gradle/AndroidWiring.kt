package dev.useunion.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Project

/** Everything that touches the Android Gradle Plugin API. Loaded only when `com.android.application` is applied. */
internal object AndroidWiring {
    fun wire(project: Project, extension: UnionExtension) {
        val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        components.onVariants { variant ->
            if (!extension.enabled.get()) return@onVariants
            if (variant.debuggable || !variant.isMinifyEnabled) return@onVariants

            val name = variant.name
            val capitalized = name.replaceFirstChar { it.uppercase() }

            val generate = project.tasks.register("generateUnionBuildId$capitalized", GenerateBuildIdTask::class.java) {
                it.outputDir.set(project.layout.buildDirectory.dir("generated/union/buildId/$name"))
            }
            variant.sources.assets?.addGeneratedSourceDirectory(generate, GenerateBuildIdTask::outputDir)

            val output = variant.outputs.firstOrNull()
            val upload = project.tasks.register("uploadUnionMapping$capitalized", UploadMappingTask::class.java) {
                it.mappingFile.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
                it.buildIdFile.set(generate.flatMap { g -> g.outputDir.file(BuildIds.ASSET_NAME) })
                it.endpoint.set(extension.endpoint)
                it.serverKey.set(extension.serverKey)
                it.variantName.set(name)
                it.appVersion.set(output?.versionName?.map { v -> v } ?: project.provider { "0" })
                it.appBuild.set(output?.versionCode?.map { c -> c.toString() } ?: project.provider { "0" })
            }

            // Runs after R8, in the same build, so the mapping and the build id it is uploaded under
            // always describe the same APK.
            project.tasks.matching { it.name == "minify${capitalized}WithR8" }.configureEach { it.finalizedBy(upload) }
        }
    }
}

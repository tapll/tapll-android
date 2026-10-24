package dev.useunion.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes a fresh build id into a generated assets directory that AGP merges into the APK/AAB. Never
 * up to date: the id identifies **this build**, and a cached one would let two different builds
 * claim the same mapping.
 */
abstract class GenerateBuildIdTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        outputs.upToDateWhen { false }
        group = "union"
        description = "Generates the Union build id asset."
    }

    @TaskAction
    fun generate() {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        val id = BuildIds.generate()
        java.io.File(dir, BuildIds.ASSET_NAME).writeText(id)
        logger.info("Union: build id $id")
    }
}

/**
 * Uploads the R8 `mapping.txt` of one variant, gzipped, to Union. Any problem is a warning: a build
 * that ships must not be blocked by a symbol upload, and the missing mapping only costs readable stacks.
 */
abstract class UploadMappingTask : DefaultTask() {
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappingFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val buildIdFile: RegularFileProperty

    @get:Input
    abstract val endpoint: Property<String>

    @get:Input
    abstract val appVersion: Property<String>

    @get:Input
    abstract val appBuild: Property<String>

    @get:Input
    abstract val variantName: Property<String>

    /** `@Internal`, not `@Input`: a secret must never become part of a task's cache key or a build scan. */
    @get:Internal
    abstract val serverKey: Property<String>

    init {
        group = "union"
        description = "Uploads the R8 mapping to Union."
        // Talks to the network: never cached, never up to date.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun upload() {
        val variant = variantName.get()
        val mapping = mappingFile.orNull?.asFile
        val key = serverKey.orNull
        MappingUploader.skipReason(key, mapping)?.let {
            logger.warn("Union: mapping upload skipped for $variant: $it. Stack traces for this build will stay obfuscated in the panel.")
            return
        }
        val buildId = buildIdFile.get().asFile.readText().trim()
        when (val r = MappingUploader.upload(endpoint.get(), key!!, buildId, appVersion.get(), appBuild.get(), mapping!!)) {
            UploadResult.Uploaded -> logger.lifecycle("Union: uploaded the mapping for $variant (build id $buildId)")
            is UploadResult.Failed -> logger.warn("Union: mapping upload failed for $variant: ${r.reason}. The build is not affected.")
            is UploadResult.Skipped -> logger.warn("Union: mapping upload skipped for $variant: ${r.reason}")
        }
    }
}

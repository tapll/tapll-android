package com.tapll.gradle

import com.sun.net.httpserver.HttpServer
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream

class PluginTests {
    @get:Rule val tmp = TemporaryFolder()

    private class Received(val headers: Map<String, String>, val body: ByteArray)

    private fun serve(status: Int, onRequest: (Received) -> Unit): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
            onRequest(Received(headers, ex.requestBody.readBytes()))
            val bytes = "{}".toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}/v1/mappings"
    }

    private fun mapping(text: String = "com.example.Foo -> a.a:\n    int bar -> a\n"): File = tmp.newFile().also { it.writeText(text) }

    @Test fun buildIdsAreLowercaseUuidV4AndUnique() {
        val ids = (1..200).map { BuildIds.generate() }
        assertEquals(200, ids.toSet().size)
        assertTrue(ids.all { BuildIds.isValid(it) && it == it.lowercase() })
        // The same alphabet the crash contract accepts for `build_id`.
        assertTrue(ids.all { Regex("^[0-9a-f-]{7,64}$").matches(it) })
        assertFalse(BuildIds.isValid("7BDAE48A-8A18-4F3E-B5D8-0090B597F61D"))
        assertFalse(BuildIds.isValid("not-a-uuid"))
    }

    @Test fun skipReasonsCoverEveryCaseThatMustNotFailTheBuild() {
        val m = mapping()
        assertEquals(null, MappingUploader.skipReason("sk_live_abc", m))
        assertTrue(MappingUploader.skipReason(null, m)!!.contains("TAPLL_SERVER_KEY"))
        assertTrue(MappingUploader.skipReason("  ", m)!!.contains("TAPLL_SERVER_KEY"))
        assertTrue("a client key ships in every APK and must never authorise a mapping", MappingUploader.skipReason("av_client", m)!!.contains("client key"))
        assertTrue(MappingUploader.skipReason("sk_x", null)!!.contains("mapping.txt"))
        assertTrue(MappingUploader.skipReason("sk_x", File(tmp.root, "missing.txt"))!!.contains("mapping.txt"))
        assertTrue(MappingUploader.skipReason("sk_x", mapping(""))!!.contains("empty"))
    }

    @Test fun theUploadIsAGzippedPostWithTheFourHeadersTheContractNames() {
        val got = AtomicReference<Received?>(null)
        val (server, url) = serve(200) { got.set(it) }
        try {
            val text = "com.example.Foo -> a.a:\n    int bar -> a\n"
            val result = MappingUploader.upload(url, "sk_live_abc", "9f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f", "1.2.3", "45", mapping(text))
            assertEquals(UploadResult.Uploaded, result)
            val r = got.get()!!
            assertEquals("sk_live_abc", r.headers["x-tapll-key"])
            assertEquals("9f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f", r.headers["x-tapll-build-id"])
            assertEquals("1.2.3", r.headers["x-tapll-app-version"])
            assertEquals("45", r.headers["x-tapll-app-build"])
            assertEquals("gzip", r.headers["content-encoding"])
            assertEquals(text, GZIPInputStream(r.body.inputStream()).readBytes().toString(Charsets.UTF_8))
        } finally {
            server.stop(0)
        }
    }

    @Test fun aRefusedOrUnreachableUploadIsAFailureResultNeverAnException() {
        val (server, url) = serve(403) { }
        try {
            val refused = MappingUploader.upload(url, "sk_x", "id", "1", "1", mapping())
            assertTrue(refused is UploadResult.Failed && refused.reason.contains("403"))
        } finally {
            server.stop(0)
        }
        val unreachable = MappingUploader.upload("http://127.0.0.1:1/v1/mappings", "sk_x", "id", "1", "1", mapping(), timeoutMs = 500)
        assertTrue(unreachable is UploadResult.Failed)
        assertTrue(MappingUploader.upload("not a url", "sk_x", "id", "1", "1", mapping()) is UploadResult.Failed)
    }

    @Test fun theExtensionDefaultsToTheHostedEndpointAndTheEnvironmentKey() {
        val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder()).build()
        project.pluginManager.apply("com.tapll")
        val ext = project.extensions.getByType(TapllExtension::class.java)
        assertEquals("https://in.tapll.com/v1/mappings", ext.endpoint.get())
        assertTrue(ext.enabled.get())
        // Whatever the environment holds, reading it must not throw; with no variable set the property is simply absent.
        assertEquals(System.getenv("TAPLL_SERVER_KEY") ?: System.getenv("UNION_SERVER_KEY"), ext.serverKey.orNull)
        ext.endpoint.set("https://staging.example.com/v1/mappings")
        assertEquals("https://staging.example.com/v1/mappings", ext.endpoint.get())
    }

    @Test fun theBuildIdTaskWritesAValidIdAndIsNeverUpToDate() {
        val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder()).build()
        val task = project.tasks.register("gen", GenerateBuildIdTask::class.java) { it.outputDir.set(project.layout.buildDirectory.dir("out")) }.get()
        task.generate()
        val first = File(task.outputDir.get().asFile, BuildIds.ASSET_NAME).readText()
        assertTrue(BuildIds.isValid(first))
        task.generate()
        assertTrue("a fresh id per run", File(task.outputDir.get().asFile, BuildIds.ASSET_NAME).readText() != first)
        assertFalse(task.outputs.upToDateSpec.isSatisfiedBy(task))
    }

    private fun uploadTask(project: org.gradle.api.Project, key: String?, endpoint: String, mapping: File?, buildId: File): UploadMappingTask =
        project.tasks.register("up", UploadMappingTask::class.java) { t ->
            if (mapping != null) t.mappingFile.set(mapping)
            t.buildIdFile.set(buildId)
            t.endpoint.set(endpoint)
            t.appVersion.set("1.0")
            t.appBuild.set("7")
            t.variantName.set("release")
            if (key != null) t.serverKey.set(key)
        }.get()

    @Test fun theUploadTaskSkipsWithoutAKeyAndDoesNotFailTheBuild() {
        val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder()).build()
        val hit = java.util.concurrent.atomic.AtomicInteger()
        val (server, url) = serve(200) { hit.incrementAndGet() }
        try {
            val id = tmp.newFile().also { it.writeText(BuildIds.generate()) }
            uploadTask(project, key = null, endpoint = url, mapping = mapping(), buildId = id).upload()
            uploadTask(ProjectBuilder.builder().withProjectDir(tmp.newFolder()).build(), key = "av_client", endpoint = url, mapping = mapping(), buildId = id).upload()
            uploadTask(ProjectBuilder.builder().withProjectDir(tmp.newFolder()).build(), key = "sk_x", endpoint = url, mapping = null, buildId = id).upload()
            assertEquals("nothing was sent", 0, hit.get())
        } finally {
            server.stop(0)
        }
    }

    @Test fun theUploadTaskSendsTheBuildIdItReadFromTheGeneratedAsset() {
        val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder()).build()
        val got = AtomicReference<Received?>(null)
        val (server, url) = serve(200) { got.set(it) }
        try {
            val buildId = BuildIds.generate()
            uploadTask(project, key = "sk_live_abc", endpoint = url, mapping = mapping(), buildId = tmp.newFile().also { it.writeText("$buildId\n") }).upload()
            assertNotNull(got.get())
            assertEquals(buildId, got.get()!!.headers["x-tapll-build-id"])
            assertEquals("7", got.get()!!.headers["x-tapll-app-build"])
        } finally {
            server.stop(0)
        }
    }

    @Test fun theUploadTaskNeverLeaksTheKeyIntoItsInputs() {
        val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder()).build()
        val task = uploadTask(project, key = "sk_secret_value", endpoint = "http://127.0.0.1:1/", mapping = mapping(), buildId = tmp.newFile().also { it.writeText("x") })
        val inputValues = task.inputs.properties.values.map { it.toString() }
        assertFalse("the key is @Internal so it is never part of a cache key or a build scan", inputValues.any { it.contains("sk_secret_value") })
    }
}

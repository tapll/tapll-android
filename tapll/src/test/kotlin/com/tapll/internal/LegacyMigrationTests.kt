package com.tapll.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LegacyMigrationTests {
    @get:Rule val tmp = TemporaryFolder()

    private fun File.put(path: String, text: String) = File(this, path).also { it.parentFile!!.mkdirs(); it.writeText(text) }
    private fun File.read(path: String) = File(this, path).readText()

    @Test fun oldOnlyIsMovedAndTheOldTreeIsRemoved() {
        val root = tmp.newFolder()
        root.put("Union/abc/identity.json", """{"install_id":"old-id"}""")
        root.put("Union/abc/queue.ndjson", "{\"e\":1}\n")
        root.put("Union/abc/crashes/c1.report.json", "{}")
        LegacyMigration.run(root)
        assertEquals("""{"install_id":"old-id"}""", root.read("Tapll/abc/identity.json"))
        assertEquals("{\"e\":1}\n", root.read("Tapll/abc/queue.ndjson"))
        assertEquals("{}", root.read("Tapll/abc/crashes/c1.report.json"))
        assertFalse(File(root, "Union").exists())
    }

    @Test fun whenBothExistTheNewOneWinsAndTheOldIsRemoved() {
        val root = tmp.newFolder()
        root.put("Union/abc/identity.json", "old")
        root.put("Union/abc/queue.ndjson", "old-queue")
        root.put("Tapll/abc/identity.json", "new")
        LegacyMigration.run(root)
        assertEquals("new", root.read("Tapll/abc/identity.json"))
        assertEquals("old-queue", root.read("Tapll/abc/queue.ndjson")) // only the old one had it
        assertFalse(File(root, "Union").exists())
    }

    @Test fun neitherIsANoOpAndRunningTwiceIsHarmless() {
        val root = tmp.newFolder()
        LegacyMigration.run(root)
        assertTrue(root.listFiles().isNullOrEmpty())
        root.put("Tapll/abc/identity.json", "new")
        LegacyMigration.run(root)
        LegacyMigration.run(root)
        assertEquals("new", root.read("Tapll/abc/identity.json"))
    }
}

package me.rerere.workspace

import java.io.File
import java.nio.file.Files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootfsShellDetectionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `hasRootfs accepts an absolute symlink bin-sh like alpine`() {
        val manager = WorkspaceManager(tmp.root)
        val bin = File(tmp.root, "ws/linux/bin").apply { mkdirs() }
        // Alpine minirootfs: /bin/sh -> /bin/busybox (absolute symlink).
        // File.isFile follows it on the host where /bin/busybox does not exist.
        Files.createSymbolicLink(File(bin, "sh").toPath(), File("/bin/busybox").toPath())

        assertTrue("absolute symlink bin/sh must count as present", manager.hasRootfs("ws"))
    }

    @Test
    fun `hasRootfs accepts a regular bin-sh`() {
        val manager = WorkspaceManager(tmp.root)
        val bin = File(tmp.root, "ws2/linux/bin").apply { mkdirs() }
        File(bin, "sh").writeText("#!/bin/sh\n")

        assertTrue(manager.hasRootfs("ws2"))
    }

    @Test
    fun `hasRootfs is false without a shell`() {
        val manager = WorkspaceManager(tmp.root)
        File(tmp.root, "ws3/linux/bin").mkdirs()

        assertFalse(manager.hasRootfs("ws3"))
    }
}

package org.ergoutree.compress.infrastructure

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.ergoutree.compress.infrastructure.files.*
import org.ergoutree.compress.ports.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.*

class ScannerTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun depthZeroOneFourAndUnlimitedHaveExplicitParentSemantics() = runTest {
        val root = temporary.newFolder("中文 空格").toPath()
        (0..5).forEach { level ->
            val dir = (0 until level).fold(root) { p, i -> p.resolve("层$i") }
            Files.createDirectories(dir); Files.write(dir.resolve("$level.mp4"), ByteArray(10))
        }
        for ((depth, expected) in listOf(0 to 1, 1 to 2, 4 to 5, null to 6)) {
            val events = NioScanner().scan(ScanRequest(listOf(root.local()), depth), StopSignal()).toList()
            assertEquals(expected, events.filterIsInstance<ScanEvent.Found>().size)
            assertEquals(expected * 10L, events.filterIsInstance<ScanEvent.Finished>().single().totalBytes)
        }
    }
    @Test fun overlappingRootsHardLinksAndOutputAreDeduplicated() = runTest {
        val root = temporary.newFolder().toPath()
        val nested = Files.createDirectory(root.resolve("nested"))
        val output = Files.createDirectory(root.resolve("output"))
        val source = Files.write(root.resolve("a.mp4"), ByteArray(20))
        Files.write(nested.resolve("b.mp4"), ByteArray(30))
        Files.write(output.resolve("generated.mp4"), ByteArray(100))
        Files.createLink(root.resolve("alias.mp4"), source)
        val events = NioScanner().scan(ScanRequest(listOf(root.local(), nested.local()), 4, listOf(output.local())), StopSignal()).toList()
        assertEquals(2, events.filterIsInstance<ScanEvent.Found>().size)
        assertEquals(50, events.filterIsInstance<ScanEvent.Finished>().single().totalBytes)
    }
    @Test fun explicitDeeperRootIsNotLostAfterFiniteParentScan() = runTest {
        val root = temporary.newFolder().toPath()
        val child = Files.createDirectories(root.resolve("a/b"))
        Files.write(child.resolve("c.mp4"), byteArrayOf(1))
        val events = NioScanner().scan(ScanRequest(listOf(root.local(), child.local()), 0), StopSignal()).toList()
        assertEquals(1, events.filterIsInstance<ScanEvent.Found>().size)
    }
    @Test fun stoppedScanAndUnreadableInputNeverClaimCompleteness() = runTest {
        val root = temporary.newFolder().toPath()
        Files.write(root.resolve("small.mp4"), byteArrayOf(1))
        val stop = StopSignal().apply { request() }
        assertFalse(NioScanner().scan(ScanRequest(listOf(root.local())), stop).toList().filterIsInstance<ScanEvent.Finished>().single().complete)
        val events = NioScanner().scan(ScanRequest(listOf(root.resolve("missing").local())), StopSignal()).toList()
        assertTrue(events.any { it is ScanEvent.Issue }); assertFalse(events.filterIsInstance<ScanEvent.Finished>().single().complete)
    }
    @Test fun thresholdAndInjectedReparseBoundaryAreRespected() = runTest {
        val root = temporary.newFolder().toPath()
        val junction = Files.createDirectory(root.resolve("junction"))
        Files.write(junction.resolve("hidden.mp4"), ByteArray(100))
        Files.write(root.resolve("small.mp4"), ByteArray(9))
        val events = NioScanner { it == junction }.scan(ScanRequest(listOf(root.local()), minimumBytes = 10), StopSignal()).toList()
        assertFalse(events.filterIsInstance<ScanEvent.Found>().single().aboveThreshold)
    }
}

package net.brucegroup.memorybridge

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class SafetyContractTest {
    @Test
    fun `production source has no Android content logging`() {
        val sourceRoot = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val sources = sourceRoot.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse(sources.contains("android.util.Log"))
        assertFalse(sources.contains("Log.d("))
        assertFalse(sources.contains("Log.i("))
    }
}

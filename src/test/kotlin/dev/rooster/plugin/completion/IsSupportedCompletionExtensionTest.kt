package dev.rooster.plugin.completion

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IsSupportedCompletionExtensionTest {

    @Test
    fun `accepts python and shell`() {
        listOf("py", "pyi", "sh", "bash", "zsh", "PY", "Sh").forEach {
            assertTrue(isSupportedCompletionExtension(it), "should accept $it")
        }
    }

    @Test
    fun `rejects everything else, including null`() {
        listOf("kt", "java", "md", "txt", "", null).forEach {
            assertFalse(isSupportedCompletionExtension(it), "should reject $it")
        }
    }
}

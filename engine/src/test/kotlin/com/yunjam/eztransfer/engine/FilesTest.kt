package com.yunjam.eztransfer.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FilesTest {
    @Test
    fun sanitizeStripsPathTraversal() {
        assertEquals("passwd", sanitizeFileName("../../etc/passwd"))
        assertEquals("evil.exe", sanitizeFileName("..\\..\\Windows\\evil.exe"))
        assertEquals("file", sanitizeFileName(".."))
        assertEquals("file", sanitizeFileName(""))
    }

    @Test
    fun sanitizeHandlesWindowsRules() {
        assertEquals("ab.txt", sanitizeFileName("a<>:\"|?*b.txt"))
        assertEquals("name", sanitizeFileName("name. . "))
        assertEquals("_CON.txt", sanitizeFileName("CON.txt"))
        assertEquals("_nul", sanitizeFileName("nul"))
        assertEquals("console.txt", sanitizeFileName("console.txt"))
    }

    @Test
    fun sanitizeKeepsExtensionWhenTruncating() {
        val result = sanitizeFileName("a".repeat(300) + ".jpeg")
        assertTrue(result.length <= 200)
        assertTrue(result.endsWith(".jpeg"))
    }

    @Test
    fun uniqueNameNumbersBeforeExtension() {
        val taken = setOf("report.pdf", "report (1).pdf", "README")
        assertEquals("report (2).pdf", uniqueName("report.pdf") { it in taken })
        assertEquals("README (1)", uniqueName("README") { it in taken })
        assertEquals("other.pdf", uniqueName("other.pdf") { it in taken })
    }

    @Test
    fun partialNameDependsOnSenderNameAndSize() {
        val base = partialFileName("device-a", "a.bin", 10)
        assertEquals(base, partialFileName("device-a", "a.bin", 10))
        assertNotEquals(base, partialFileName("device-b", "a.bin", 10))
        assertNotEquals(base, partialFileName("device-a", "a.bin", 11))
        assertTrue(base.startsWith(".") && base.endsWith(".part"))
    }
}

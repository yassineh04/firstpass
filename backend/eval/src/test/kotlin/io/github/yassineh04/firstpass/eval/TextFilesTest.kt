package io.github.yassineh04.firstpass.eval

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Windows PowerShell 5.1 writes UTF-16 when output is redirected with `>`; editors may add a UTF-8 BOM. */
class TextFilesTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `reads UTF-8 with or without a byte order mark`() {
        read(TEXT.toByteArray(Charsets.UTF_8)) shouldBe TEXT
        read(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + TEXT.toByteArray(Charsets.UTF_8)) shouldBe TEXT
    }

    @Test
    fun `reads UTF-16 in both byte orders when a byte order mark says so`() {
        read(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + TEXT.toByteArray(Charsets.UTF_16LE)) shouldBe TEXT
        read(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + TEXT.toByteArray(Charsets.UTF_16BE)) shouldBe TEXT
    }

    private fun read(bytes: ByteArray): String {
        val file = dir.resolve("text-${bytes.size}-${bytes[0]}.patch")
        Files.write(file, bytes)
        return TextFiles.read(file)
    }

    private companion object {
        const val TEXT = "diff --git a/Café.kt b/Café.kt\n+val x = 1\n"
    }
}

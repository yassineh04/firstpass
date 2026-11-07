package io.github.yassineh04.firstpass.core.diff

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class UnifiedDiffParserTest {
    @Test
    fun `splits a git diff into one file per diff header`() {
        val diff =
            """
            diff --git a/src/Main.kt b/src/Main.kt
            index 83db48f..bf269f4 100644
            --- a/src/Main.kt
            +++ b/src/Main.kt
            @@ -1,2 +1,2 @@
             fun main() {
            -    println("hi")
            +    println("hello")
            diff --git a/docs/new.md b/docs/new.md
            new file mode 100644
            index 0000000..e69de29
            --- /dev/null
            +++ b/docs/new.md
            @@ -0,0 +1 @@
            +# New
            """.trimIndent()

        UnifiedDiffParser.parse(diff) shouldContainExactly
            listOf(
                DiffFile(
                    path = "src/Main.kt",
                    status = FileStatus.MODIFIED,
                    patch = "@@ -1,2 +1,2 @@\n fun main() {\n-    println(\"hi\")\n+    println(\"hello\")",
                ),
                DiffFile(path = "docs/new.md", status = FileStatus.ADDED, patch = "@@ -0,0 +1 @@\n+# New"),
            )
    }

    @Test
    fun `takes the path of a deleted file from the old side`() {
        val diff =
            """
            diff --git a/old.txt b/old.txt
            deleted file mode 100644
            index 3b18e51..0000000
            --- a/old.txt
            +++ /dev/null
            @@ -1 +0,0 @@
            -bye
            """.trimIndent()

        UnifiedDiffParser.parse(diff) shouldContainExactly
            listOf(DiffFile(path = "old.txt", status = FileStatus.REMOVED, patch = "@@ -1 +0,0 @@\n-bye"))
    }

    @Test
    fun `keeps the previous path of a renamed file`() {
        val diff =
            """
            diff --git a/src/Old.kt b/src/New.kt
            similarity index 90%
            rename from src/Old.kt
            rename to src/New.kt
            index 1111111..2222222 100644
            --- a/src/Old.kt
            +++ b/src/New.kt
            @@ -1 +1 @@
            -class Old
            +class New
            """.trimIndent()

        UnifiedDiffParser.parse(diff) shouldContainExactly
            listOf(
                DiffFile(
                    path = "src/New.kt",
                    status = FileStatus.RENAMED,
                    patch = "@@ -1 +1 @@\n-class Old\n+class New",
                    previousPath = "src/Old.kt",
                ),
            )
    }

    @Test
    fun `gives no patch to a pure rename or a binary file`() {
        val diff =
            """
            diff --git a/a.txt b/b.txt
            similarity index 100%
            rename from a.txt
            rename to b.txt
            diff --git a/logo.png b/logo.png
            new file mode 100644
            index 0000000..d3f5a12
            Binary files /dev/null and b/logo.png differ
            """.trimIndent()

        UnifiedDiffParser.parse(diff) shouldContainExactly
            listOf(
                DiffFile(path = "b.txt", status = FileStatus.RENAMED, patch = null, previousPath = "a.txt"),
                DiffFile(path = "logo.png", status = FileStatus.ADDED, patch = null),
            )
    }

    @Test
    fun `ignores the email headers and signature of git format-patch`() {
        val patch =
            """
            From 1234abcd Mon Sep 17 00:00:00 2001
            From: Dev <dev@example.com>
            Subject: [PATCH] Fix greeting

            ---
             src/Main.kt | 2 +-
             1 file changed

            diff --git a/src/Main.kt b/src/Main.kt
            index 83db48f..bf269f4 100644
            --- a/src/Main.kt
            +++ b/src/Main.kt
            @@ -1 +1 @@
            -println("hi")
            +println("hello")
            --
            2.45.1
            """.trimIndent()

        val files = UnifiedDiffParser.parse(patch)

        files shouldHaveSize 1
        files[0].path shouldBe "src/Main.kt"
        HunkParser.parse(files[0].patch!!)[0].lines shouldContainExactly
            listOf(RemovedLine(1, "println(\"hi\")"), AddedLine(1, "println(\"hello\")"))
    }

    @Test
    fun `returns no file for an empty diff`() {
        UnifiedDiffParser.parse("").shouldBeEmpty()
    }
}

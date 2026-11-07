package io.github.yassineh04.firstpass.core.diff

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HunkParserTest {
    @Test
    fun `numbers context, added and removed lines from the hunk header`() {
        val patch =
            """
            @@ -40,4 +40,5 @@ class UserService {
             fun findUser(id: String): User {
            -    val user = repo.find(id)
            +    val user = repo.find(id)!!
            +    log.info("found")
                 return user
             }
            """.trimIndent()

        val hunks = HunkParser.parse(patch)

        hunks shouldHaveSize 1
        hunks[0].header shouldBe "@@ -40,4 +40,5 @@ class UserService {"
        hunks[0].lines shouldContainExactly
            listOf(
                ContextLine(oldLine = 40, newLine = 40, content = "fun findUser(id: String): User {"),
                RemovedLine(oldLine = 41, content = "    val user = repo.find(id)"),
                AddedLine(newLine = 41, content = "    val user = repo.find(id)!!"),
                AddedLine(newLine = 42, content = "    log.info(\"found\")"),
                ContextLine(oldLine = 42, newLine = 43, content = "    return user"),
                ContextLine(oldLine = 43, newLine = 44, content = "}"),
            )
    }

    @Test
    fun `defaults the line counts to 1 when the header omits them`() {
        val hunks = HunkParser.parse("@@ -1 +1 @@\n-old\n+new")

        hunks[0].oldCount shouldBe 1
        hunks[0].newCount shouldBe 1
        hunks[0].lines shouldContainExactly listOf(RemovedLine(1, "old"), AddedLine(1, "new"))
    }

    @Test
    fun `numbers the lines of a new file from 1`() {
        val hunks = HunkParser.parse("@@ -0,0 +1,2 @@\n+first\n+second")

        hunks[0].lines shouldContainExactly listOf(AddedLine(1, "first"), AddedLine(2, "second"))
    }

    @Test
    fun `ignores the no-newline-at-end-of-file marker`() {
        val patch = "@@ -1 +1 @@\n-old\n\\ No newline at end of file\n+new\n\\ No newline at end of file"

        HunkParser.parse(patch)[0].lines shouldContainExactly listOf(RemovedLine(1, "old"), AddedLine(1, "new"))
    }

    @Test
    fun `accepts Windows line endings`() {
        val patch = "@@ -1,2 +1,2 @@\r\n line one\r\n-old\r\n+new\r\n"

        HunkParser.parse(patch)[0].lines shouldContainExactly
            listOf(ContextLine(1, 1, "line one"), RemovedLine(2, "old"), AddedLine(2, "new"))
    }

    @Test
    fun `reads an empty line as an empty context line`() {
        val patch = "@@ -1,3 +1,3 @@\n first\n\n-old\n+new"

        HunkParser.parse(patch)[0].lines shouldContainExactly
            listOf(ContextLine(1, 1, "first"), ContextLine(2, 2, ""), RemovedLine(3, "old"), AddedLine(3, "new"))
    }

    @Test
    fun `parses several hunks, each numbered from its own header`() {
        val patch = "@@ -1,2 +1,2 @@\n a\n-b\n+B\n@@ -10,1 +10,2 @@ fun tail() {\n z\n+added"

        val hunks = HunkParser.parse(patch)

        hunks shouldHaveSize 2
        hunks[1].header shouldBe "@@ -10,1 +10,2 @@ fun tail() {"
        hunks[1].lines shouldContainExactly listOf(ContextLine(10, 10, "z"), AddedLine(11, "added"))
    }

    @Test
    fun `stops each hunk once its line counts are reached`() {
        // git format-patch ends with a "-- " signature line, which looks like a removed line.
        val patch = "@@ -1 +1 @@\n-old\n+new\n-- \n2.45.1"

        HunkParser.parse(patch)[0].lines shouldContainExactly listOf(RemovedLine(1, "old"), AddedLine(1, "new"))
    }

    @Test
    fun `rejects a malformed hunk header`() {
        shouldThrow<IllegalArgumentException> { HunkParser.parse("@@ -x +1 @@\n+a") }
    }

    @Test
    fun `returns no hunk for an empty patch`() {
        HunkParser.parse("").shouldBeEmpty()
    }
}

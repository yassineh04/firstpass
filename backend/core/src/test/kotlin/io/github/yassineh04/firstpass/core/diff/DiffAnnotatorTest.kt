package io.github.yassineh04.firstpass.core.diff

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DiffAnnotatorTest {
    private val patch =
        """
        @@ -40,4 +40,5 @@ class UserService {
         fun findUser(id: String): User {
        -    val user = repo.find(id)
        +    val user = repo.find(id)!!
        +    log.info("found")
             return user
         }
        @@ -90,1 +91,2 @@
         fun tail() = Unit
        +fun added() = Unit
        """.trimIndent()

    private val annotated =
        DiffAnnotator
            .annotate(
                DiffFile("src/UserService.kt", FileStatus.MODIFIED, patch),
            ).shouldNotBeNull()

    @Test
    fun `renders added lines with their number, context lines indented and removed lines without number`() {
        annotated.render() shouldBe
            """
            @@ -40,4 +40,5 @@ class UserService {
              40 | fun findUser(id: String): User {
            - |     val user = repo.find(id)
            + 41 |     val user = repo.find(id)!!
            + 42 |     log.info("found")
              43 |     return user
              44 | }
            @@ -90,1 +91,2 @@
              91 | fun tail() = Unit
            + 92 | fun added() = Unit
            """.trimIndent()
    }

    @Test
    fun `allows comments only on added lines by default`() {
        annotated.isCommentable(41, CommentOn.ADDED_LINES) shouldBe true
        annotated.isCommentable(92, CommentOn.ADDED_LINES) shouldBe true
        annotated.isCommentable(40, CommentOn.ADDED_LINES) shouldBe false
        annotated.isCommentable(60, CommentOn.ADDED_LINES) shouldBe false
    }

    @Test
    fun `also allows comments on context lines with diff_context`() {
        annotated.isCommentable(40, CommentOn.DIFF_CONTEXT) shouldBe true
        annotated.isCommentable(91, CommentOn.DIFF_CONTEXT) shouldBe true
        annotated.isCommentable(60, CommentOn.DIFF_CONTEXT) shouldBe false
    }

    @Test
    fun `accepts a multi-line range only inside one hunk`() {
        annotated.isCommentableRange(41, 42, CommentOn.ADDED_LINES) shouldBe true
        annotated.isCommentableRange(40, 42, CommentOn.ADDED_LINES) shouldBe false
        annotated.isCommentableRange(40, 42, CommentOn.DIFF_CONTEXT) shouldBe true
        annotated.isCommentableRange(42, 41, CommentOn.ADDED_LINES) shouldBe false
        annotated.isCommentableRange(42, 92, CommentOn.ADDED_LINES) shouldBe false
    }

    @Test
    fun `gives nothing to review for a file without patch`() {
        DiffAnnotator.annotate(DiffFile("logo.png", FileStatus.ADDED, patch = null)).shouldBeNull()
        DiffAnnotator.annotate(DiffFile("b.txt", FileStatus.RENAMED, patch = "", previousPath = "a.txt")).shouldBeNull()
    }
}

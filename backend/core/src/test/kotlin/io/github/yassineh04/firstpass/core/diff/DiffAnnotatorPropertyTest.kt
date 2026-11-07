package io.github.yassineh04.firstpass.core.diff

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.printableAscii
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Property-based tests: random files and random edits produce a patch whose expected
 * old and new files are known. Every number the annotator gives must point to the right line.
 */
class DiffAnnotatorPropertyTest {
    @Test
    fun `every numbered line points to the right line of the old and new files`() {
        runBlocking {
            checkAll(ITERATIONS, generatedDiffs) { generated ->
                for (line in annotate(generated).hunks.flatMap { it.lines }) {
                    when (line) {
                        is AddedLine -> {
                            generated.newLines[line.newLine - 1] shouldBe line.content
                        }

                        is RemovedLine -> {
                            generated.oldLines[line.oldLine - 1] shouldBe line.content
                        }

                        is ContextLine -> {
                            generated.newLines[line.newLine - 1] shouldBe line.content
                            generated.oldLines[line.oldLine - 1] shouldBe line.content
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `every number in the rendered text points to the right line of the new file`() {
        runBlocking {
            checkAll(ITERATIONS, generatedDiffs) { generated ->
                for (renderedLine in annotate(generated).render().lines()) {
                    val match = NUMBERED_LINE.matchEntire(renderedLine) ?: continue
                    val (number, content) = match.destructured
                    generated.newLines[number.toInt() - 1] shouldBe content
                }
            }
        }
    }

    @Test
    fun `added lines are always commentable, context lines only with diff_context`() {
        runBlocking {
            checkAll(ITERATIONS, generatedDiffs) { generated ->
                val annotated = annotate(generated)
                for (line in annotated.hunks.flatMap { it.lines }) {
                    when (line) {
                        is AddedLine -> annotated.isCommentable(line.newLine, CommentOn.ADDED_LINES) shouldBe true
                        is ContextLine -> annotated.isCommentable(line.newLine, CommentOn.DIFF_CONTEXT) shouldBe true
                        is RemovedLine -> Unit
                    }
                }
            }
        }
    }

    private fun annotate(generated: GeneratedDiff): AnnotatedFile =
        DiffAnnotator.annotate(DiffFile("Generated.kt", FileStatus.MODIFIED, generated.patch)).shouldNotBeNull()

    private sealed interface Edit {
        val text: String
    }

    private data class Keep(
        override val text: String,
    ) : Edit

    private data class Add(
        override val text: String,
    ) : Edit

    private data class Remove(
        override val text: String,
    ) : Edit

    /** Unchanged lines left out of the patch, then one hunk of edits. */
    private data class Segment(
        val gap: List<String>,
        val edits: List<Edit>,
    )

    private data class GeneratedDiff(
        val patch: String,
        val oldLines: List<String>,
        val newLines: List<String>,
    )

    private companion object {
        const val ITERATIONS = 500
        val NUMBERED_LINE = Regex("""^[+ ] (\d+) \| (.*)$""")

        val lineText: Arb<String> = Arb.string(0..12, Codepoint.printableAscii())

        val edits: Arb<Edit> =
            Arb.choice(
                lineText.map<String, Edit> { Keep(it) },
                lineText.map<String, Edit> { Add(it) },
                lineText.map<String, Edit> { Remove(it) },
            )

        val segments: Arb<Segment> =
            Arb.bind(Arb.list(lineText, 0..4), Arb.list(edits, 1..8)) { gap, edits -> Segment(gap, edits) }

        val generatedDiffs: Arb<GeneratedDiff> =
            Arb.bind(Arb.list(segments, 1..4), Arb.list(lineText, 0..3)) { segments, tail -> build(segments, tail) }

        fun build(
            segments: List<Segment>,
            tail: List<String>,
        ): GeneratedDiff {
            val oldLines = mutableListOf<String>()
            val newLines = mutableListOf<String>()
            val patch = StringBuilder()
            for (segment in segments) {
                oldLines += segment.gap
                newLines += segment.gap
                val oldCount = segment.edits.count { it !is Add }
                val newCount = segment.edits.count { it !is Remove }
                // Unified diff convention: an empty side points at the line before the change.
                val oldStart = if (oldCount == 0) oldLines.size else oldLines.size + 1
                val newStart = if (newCount == 0) newLines.size else newLines.size + 1
                patch.append("@@ -$oldStart,$oldCount +$newStart,$newCount @@\n")
                for (edit in segment.edits) {
                    when (edit) {
                        is Keep -> {
                            patch.append(' ').append(edit.text).append('\n')
                            oldLines += edit.text
                            newLines += edit.text
                        }

                        is Add -> {
                            patch.append('+').append(edit.text).append('\n')
                            newLines += edit.text
                        }

                        is Remove -> {
                            patch.append('-').append(edit.text).append('\n')
                            oldLines += edit.text
                        }
                    }
                }
            }
            oldLines += tail
            newLines += tail
            return GeneratedDiff(patch.toString().removeSuffix("\n"), oldLines, newLines)
        }
    }
}

package io.github.yassineh04.firstpass.core.diff

/**
 * Parses the hunks of one file's patch and numbers every line.
 *
 * A hunk ends as soon as the line counts of its header are reached, so trailing text such as
 * the `-- ` signature of `git format-patch` is ignored. The `\ No newline at end of file`
 * marker is skipped. Windows line endings are accepted.
 */
object HunkParser {
    private val HEADER = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*$""")

    fun parse(patch: String): List<Hunk> {
        val lines = patch.lines().map { it.removeSuffix("\r") }
        val hunks = mutableListOf<Hunk>()
        var index = 0
        while (index < lines.size) {
            if (lines[index].startsWith("@@")) {
                val builder = HunkBuilder.fromHeader(lines[index])
                index++
                while (index < lines.size && !builder.isComplete && !lines[index].startsWith("@@")) {
                    builder.accept(lines[index])
                    index++
                }
                hunks += builder.build()
            } else {
                index++
            }
        }
        return hunks
    }

    private class HunkBuilder(
        private val header: String,
        private val oldStart: Int,
        private val oldCount: Int,
        private val newStart: Int,
        private val newCount: Int,
    ) {
        private val lines = mutableListOf<DiffLine>()
        private var oldLine = oldStart
        private var newLine = newStart
        private var remainingOld = oldCount
        private var remainingNew = newCount

        val isComplete: Boolean
            get() = remainingOld <= 0 && remainingNew <= 0

        fun accept(raw: String) {
            when (raw.firstOrNull()) {
                '+' -> {
                    lines += AddedLine(newLine++, raw.substring(1))
                    remainingNew--
                }

                '-' -> {
                    lines += RemovedLine(oldLine++, raw.substring(1))
                    remainingOld--
                }

                // Some tools strip the space of an empty context line, leaving an empty line.
                ' ', null -> {
                    lines += ContextLine(oldLine++, newLine++, raw.drop(1))
                    remainingOld--
                    remainingNew--
                }

                else -> {
                    // "\ No newline at end of file" and any other marker carry no file line.
                }
            }
        }

        fun build(): Hunk = Hunk(header, oldStart, oldCount, newStart, newCount, lines.toList())

        companion object {
            private const val OLD_START = 1
            private const val OLD_COUNT = 2
            private const val NEW_START = 3
            private const val NEW_COUNT = 4

            fun fromHeader(header: String): HunkBuilder {
                val match = requireNotNull(HEADER.matchEntire(header)) { "Malformed hunk header: $header" }

                // Groups 1 to 4: old start, old count, new start, new count. A missing count means one line.
                fun number(group: Int): Int = match.groupValues[group].ifEmpty { "1" }.toInt()

                return HunkBuilder(
                    header = header,
                    oldStart = number(OLD_START),
                    oldCount = number(OLD_COUNT),
                    newStart = number(NEW_START),
                    newCount = number(NEW_COUNT),
                )
            }
        }
    }
}

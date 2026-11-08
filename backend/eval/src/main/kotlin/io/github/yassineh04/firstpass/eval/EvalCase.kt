package io.github.yassineh04.firstpass.eval

import io.github.yassineh04.firstpass.core.finding.Category
import io.github.yassineh04.firstpass.core.finding.Severity
import tools.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path

/** A problem the review should find: file, new-file line, category and severity. */
internal data class ExpectedFinding(
    val path: String,
    val line: Int,
    val category: Category,
    val severity: Severity,
)

/**
 * One evaluation case: a git diff with known problems. [title] is the pull request title given to
 * the model; [description] explains the case to humans and is never sent to the model. A case
 * without expected findings is a clean diff, used to measure noise.
 */
internal data class EvalCase(
    val id: String,
    val title: String,
    val description: String,
    val patch: String,
    val expected: List<ExpectedFinding>,
) {
    val clean: Boolean get() = expected.isEmpty()
}

/**
 * Reads `<id>/diff.patch` and `<id>/expected.json` for every case folder, sorted by id. In
 * expected.json, categories and severities use the names of the review output schema.
 */
internal object CaseLoader {
    private const val PATCH = "diff.patch"
    private const val EXPECTED = "expected.json"

    fun load(directory: Path): List<EvalCase> {
        if (!Files.isDirectory(directory)) {
            throw CliError("No evaluation case found: ${directory.toAbsolutePath()} is not a folder")
        }
        val folders =
            Files
                .list(directory)
                .use { entries -> entries.filter { Files.isDirectory(it) }.toList() }
                .sortedBy { it.fileName.toString() }
        if (folders.isEmpty()) throw CliError("No evaluation case found in ${directory.toAbsolutePath()}")
        return folders.map(::loadCase)
    }

    private fun loadCase(folder: Path): EvalCase {
        val id = folder.fileName.toString()
        val json = JSON.readTree(TextFiles.read(file(folder, id, EXPECTED)))
        return EvalCase(
            id = id,
            title = text(json, "title", id),
            description = text(json, "description", id),
            patch = TextFiles.read(file(folder, id, PATCH)),
            expected = json.path("expected").values().map { expectedFinding(it, id) },
        )
    }

    private fun expectedFinding(
        node: JsonNode,
        id: String,
    ): ExpectedFinding =
        ExpectedFinding(
            path = text(node, "path", id),
            line = positiveLine(node.path("line"), id),
            category = named(Category.entries, text(node, "category", id), "category", id) { it.wireName },
            severity = named(Severity.entries, text(node, "severity", id), "severity", id) { it.wireName },
        )

    private fun positiveLine(
        node: JsonNode,
        id: String,
    ): Int =
        node.takeIf { it.isNumber }?.asInt()?.takeIf { it >= 1 }
            ?: throw CliError("Case $id: every expected line must be a positive number")

    /** The value of [values] whose schema name is [name]. */
    private fun <T> named(
        values: List<T>,
        name: String,
        kind: String,
        id: String,
        wireName: (T) -> String,
    ): T = values.firstOrNull { wireName(it) == name } ?: throw CliError("Case $id: unknown $kind \"$name\"")

    private fun file(
        folder: Path,
        id: String,
        name: String,
    ): Path = folder.resolve(name).takeIf { Files.isRegularFile(it) } ?: throw CliError("Case $id: $name is missing")

    private fun text(
        node: JsonNode,
        field: String,
        id: String,
    ): String {
        val value = node.path(field)
        if (value.isMissingNode || value.isNull) throw CliError("Case $id: \"$field\" is missing in $EXPECTED")
        return value.asString()
    }
}

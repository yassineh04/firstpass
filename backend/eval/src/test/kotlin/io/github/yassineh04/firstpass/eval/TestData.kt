package io.github.yassineh04.firstpass.eval

import io.github.yassineh04.firstpass.core.finding.Category
import io.github.yassineh04.firstpass.core.finding.Finding
import io.github.yassineh04.firstpass.core.finding.Severity

/** A finding as the review publishes it; only path, lines and category matter to the matcher. */
internal fun finding(
    path: String,
    line: Int,
    category: Category = Category.BUG,
    startLine: Int? = null,
) = Finding(
    path = path,
    startLine = startLine,
    line = line,
    severity = Severity.MAJOR,
    category = category,
    title = "Problem on line $line",
    body = "Explained.",
    suggestion = null,
    confidence = 0.9,
    fingerprint = "$path:$line:$category",
)

internal fun expected(
    path: String,
    line: Int,
    category: Category = Category.BUG,
) = ExpectedFinding(path, line, category, Severity.MAJOR)

/** A git diff that adds [added] lines, numbered from 10, to the file at [path]. */
internal fun patchAdding(
    path: String,
    vararg added: String,
): String =
    (
        listOf(
            "diff --git a/$path b/$path",
            "index 1111111..2222222 100644",
            "--- a/$path",
            "+++ b/$path",
            "@@ -10,0 +10,${added.size} @@",
        ) + added.map { "+$it" }
    ).joinToString("\n", postfix = "\n")

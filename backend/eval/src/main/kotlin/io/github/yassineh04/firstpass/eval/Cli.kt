package io.github.yassineh04.firstpass.eval

import io.github.yassineh04.firstpass.core.diff.DiffFile
import io.github.yassineh04.firstpass.core.diff.UnifiedDiffParser
import io.github.yassineh04.firstpass.core.quota.QuotaLimits
import io.github.yassineh04.firstpass.core.quota.QuotaPolicy
import io.github.yassineh04.firstpass.core.review.PullRequestInfo
import io.github.yassineh04.firstpass.core.review.ReviewOrchestrator
import io.github.yassineh04.firstpass.core.review.ReviewOutcome
import io.github.yassineh04.firstpass.core.review.ReviewProvider
import io.github.yassineh04.firstpass.core.review.ReviewRequest
import io.github.yassineh04.firstpass.core.settings.ReviewSettings
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/**
 * The command line of the eval module. `review` sends one patch file through the same review as
 * the GitHub App, with providers chosen locally, and prints the result as JSON. Exit codes: 0 when
 * the review completed, 1 for a usage error, 2 when no review could be made.
 */
internal class Cli(
    private val settings: Map<String, String>,
    private val out: PrintStream,
    private val err: PrintStream,
    private val clock: Clock = Clock.systemUTC(),
    private val chain: (String, String?, Map<String, String>) -> List<ReviewProvider> = ProviderChain::build,
) {
    fun run(args: List<String>): Int =
        try {
            when (args.firstOrNull()) {
                "review" -> {
                    review(ReviewArguments.parse(args.drop(1)))
                }

                "help", "--help", "-h" -> {
                    out.println(USAGE)
                    EXIT_OK
                }

                null -> {
                    err.println(USAGE)
                    EXIT_ERROR
                }

                else -> {
                    throw CliError("Unknown command: ${args.first()}")
                }
            }
        } catch (error: CliError) {
            err.println("Error: ${error.message}")
            err.println("Run with --help to see the usage.")
            EXIT_ERROR
        }

    private fun review(arguments: ReviewArguments): Int {
        val files = readPatch(arguments.patch)
        val provider =
            arguments.provider ?: settings["FIRSTPASS_LLM_PROVIDER"]?.trim()?.ifEmpty { null } ?: DEFAULT_PROVIDER
        val orchestrator =
            ReviewOrchestrator(chain(provider, arguments.model, settings), QuotaPolicy(QuotaLimits(), clock))
        val title = arguments.title ?: "Local review of ${arguments.patch.fileName}"
        val outcome =
            orchestrator.review(
                ReviewRequest(
                    pullRequest = PullRequestInfo(LOCAL_PULL_REQUEST, title, description = null),
                    files = files,
                    settings = ReviewSettings(language = arguments.language),
                ),
            )
        write(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(ReviewOutput.of(outcome)), arguments.output)
        err.println(describe(outcome))
        return if (outcome is ReviewOutcome.Completed) EXIT_OK else EXIT_NOT_REVIEWED
    }

    private fun readPatch(patch: Path): List<DiffFile> {
        if (!Files.isRegularFile(patch)) throw CliError("Patch file not found: ${patch.toAbsolutePath()}")
        val files = UnifiedDiffParser.parse(TextFiles.read(patch))
        if (files.isEmpty()) throw CliError("No changed file found in $patch. Create it with: git diff --output=$patch")
        return files
    }

    private fun write(
        json: String,
        output: Path?,
    ) {
        if (output == null) {
            out.println(json)
        } else {
            output.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            Files.writeString(output, json + "\n", Charsets.UTF_8)
            err.println("Report written to ${output.toAbsolutePath()}")
        }
    }

    private fun describe(outcome: ReviewOutcome): String =
        when (outcome) {
            is ReviewOutcome.QuotaExceeded -> {
                "Review refused before any model call: ${outcome.reason}"
            }

            is ReviewOutcome.Unavailable -> {
                "No provider gave a valid answer: " +
                    outcome.report.failedBatches.joinToString("; ") { "${it.provider} ${it.reason} (${it.detail})" }
            }

            is ReviewOutcome.Completed -> {
                val report = outcome.report
                val models = report.usage.joinToString { "${it.provider} (${it.model ?: "no valid answer"})" }
                "Reviewed with ${models.ifEmpty { "no model call" }}: ${report.inline.size} inline, " +
                    "${report.summary.size} in the summary, ${report.skipped.size} skipped, " +
                    "${report.notReviewed.size} not reviewed, ${report.failedBatches.size} failed batches."
            }
        }

    private companion object {
        const val EXIT_OK = 0
        const val EXIT_ERROR = 1
        const val EXIT_NOT_REVIEWED = 2
        const val DEFAULT_PROVIDER = "fake"
        const val LOCAL_PULL_REQUEST = 1

        val USAGE =
            """
            Usage: review <file.patch> [options]

            Reviews a git diff with the same pipeline as the GitHub App and prints the result as JSON.
            Create the patch at the repository root with: git diff --output=changes.patch

            Options:
              --provider <name>   fake, ollama, gemini or groq (default: FIRSTPASS_LLM_PROVIDER, else fake)
              --model <name>      model of the main provider (required for ollama unless OLLAMA_MODEL is set)
              --title <text>      pull request title given to the model
              --language <code>   language of the comments: en (default) or fr
              --output <file>     write the JSON to this UTF-8 file instead of the console

            Settings come from environment variables first, then from the .env file at the repository root.
            """.trimIndent()
    }
}

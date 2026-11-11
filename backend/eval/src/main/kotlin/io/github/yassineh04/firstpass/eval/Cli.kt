package io.github.yassineh04.firstpass.eval

import io.github.yassineh04.firstpass.core.diff.DiffFile
import io.github.yassineh04.firstpass.core.diff.UnifiedDiffParser
import io.github.yassineh04.firstpass.core.prompt.PromptBuilder
import io.github.yassineh04.firstpass.core.quota.QuotaLimits
import io.github.yassineh04.firstpass.core.quota.QuotaPolicy
import io.github.yassineh04.firstpass.core.review.PullRequestInfo
import io.github.yassineh04.firstpass.core.review.ReviewOrchestrator
import io.github.yassineh04.firstpass.core.review.ReviewOutcome
import io.github.yassineh04.firstpass.core.review.ReviewProvider
import io.github.yassineh04.firstpass.core.review.ReviewRequest
import io.github.yassineh04.firstpass.core.settings.ReviewSettings
import io.github.yassineh04.firstpass.llm.ProviderChain
import io.github.yassineh04.firstpass.llm.ProviderSetupException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds

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
    private val chain: (String, String?, Map<String, String>) -> List<ReviewProvider> = ::providers,
) {
    fun run(args: List<String>): Int =
        try {
            when (args.firstOrNull()) {
                "review" -> {
                    review(ReviewArguments.parse(args.drop(1)))
                }

                "eval" -> {
                    evaluate(EvalArguments.parse(args.drop(1)))
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
        val provider = providerName(arguments.provider)
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

    private fun evaluate(arguments: EvalArguments): Int {
        val cases = CaseLoader.load(arguments.cases)
        val provider = providerName(arguments.provider)
        val pauseSeconds = arguments.pauseSeconds ?: if (provider == DEFAULT_PROVIDER) 0 else REMOTE_PAUSE_SECONDS
        val pacer = Pacer(pauseSeconds.seconds)
        val calls = mutableListOf<CallRecord>()
        val providers =
            chain(provider, arguments.model, settings).map {
                it.copy(llm = InstrumentedLlm(it.name, it.llm, pacer, calls))
            }
        err.println("Evaluating ${cases.size} cases, ${arguments.runs} runs each, with $provider...")
        val runs = EvalRunner(providers, clock).run(cases, arguments.runs)
        val model =
            runs
                .flatMap { it.models }
                .distinct()
                .joinToString(", ")
                .ifEmpty { provider }
        val evalSettings =
            EvalSettings(LocalDate.now(clock), provider, model, PromptBuilder.PROMPT_VERSION, arguments.runs)
        val output = arguments.output ?: Path.of("eval", "results", EvalReport.fileName(evalSettings))
        writeFile(output, EvalReport.markdown(evalSettings, runs, calls))
        val metrics = Metrics.of(runs)
        err.println(
            "Precision ${Numbers.percent(metrics.precision)}, recall ${Numbers.percent(metrics.recall)}, " +
                "noise ${Numbers.decimal(metrics.noise)} findings per clean case.",
        )
        return if (runs.all { it.outcome == Outcomes.COMPLETED }) EXIT_OK else EXIT_NOT_REVIEWED
    }

    private fun providerName(option: String?): String =
        option ?: settings["FIRSTPASS_LLM_PROVIDER"]?.trim()?.ifEmpty { null } ?: DEFAULT_PROVIDER

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
        if (output == null) out.println(json) else writeFile(output, json + "\n")
    }

    private fun writeFile(
        file: Path,
        text: String,
    ) {
        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.writeString(file, text, Charsets.UTF_8)
        err.println("Report written to ${file.toAbsolutePath()}")
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

        /** Gemini's free tier allows 15 requests a minute: one call every 4 seconds stays under it. */
        const val REMOTE_PAUSE_SECONDS = 4

        val USAGE =
            """
            Usage:
              review <file.patch> [options]   review one git diff and print the result as JSON
              eval [options]                  measure review quality on the cases in eval/cases

            Create a patch at the repository root with: git diff --output=changes.patch

            Options of both commands:
              --provider <name>   fake, ollama, gemini or groq (default: FIRSTPASS_LLM_PROVIDER, else fake)
              --model <name>      model of the main provider (required for ollama unless OLLAMA_MODEL is set)
              --output <file>     file to write (review: JSON instead of the console; eval: the Markdown report)

            Options of review:
              --title <text>      pull request title given to the model
              --language <code>   language of the comments: en (default) or fr

            Options of eval:
              --runs <n>          runs of each case (default: 2)
              --cases <folder>    folder of the cases (default: eval/cases)
              --pause <seconds>   pause between model calls (default: 4 for a remote provider, 0 for fake)

            The eval report goes to eval/results/<date>-<model>.md unless --output is given.
            Settings come from environment variables first, then from the .env file at the repository root.
            """.trimIndent()
    }
}

/** The providers of the command line; a provider that cannot be built is a problem the user can fix. */
private fun providers(
    provider: String,
    model: String?,
    settings: Map<String, String>,
): List<ReviewProvider> =
    try {
        ProviderChain.build(provider, model, settings)
    } catch (error: ProviderSetupException) {
        throw CliError(error.message.orEmpty(), error)
    }

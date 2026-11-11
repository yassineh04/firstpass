package io.github.yassineh04.firstpass.local

import io.github.yassineh04.firstpass.llm.ProviderSetupException
import io.github.yassineh04.firstpass.worker.MissingSettingException
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Entry point of `./gradlew :local-dev:run`, from the repository root. Needs DynamoDB Local
 * (`docker compose up -d`) and the development app's settings in `.env`; then relay the webhooks
 * with smee (docs/github-app-setup.md, step 7).
 */
fun main() {
    val settings = LocalEnvironment.load(Path.of(".env"))
    val port = settings["LOCAL_PORT"]?.toIntOrNull() ?: LocalApp.DEFAULT_PORT
    val app =
        try {
            LocalApp.start(settings, port)
        } catch (error: MissingSettingException) {
            fail(error.message)
        } catch (error: ProviderSetupException) {
            fail(error.message)
        }
    Runtime.getRuntime().addShutdownHook(Thread(app::close))
    println("FirstPass listens on http://127.0.0.1:${app.port}/webhook. Press Ctrl+C to stop.")
}

private fun fail(message: String?): Nothing {
    System.err.println("Error: $message")
    exitProcess(1)
}

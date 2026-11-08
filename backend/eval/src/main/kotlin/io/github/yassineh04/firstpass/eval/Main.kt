package io.github.yassineh04.firstpass.eval

import java.nio.file.Path
import kotlin.system.exitProcess

/** Entry point of `./gradlew :eval:run --args="..."`. Environment variables override the .env file. */
fun main(args: Array<String>) {
    val settings = DotEnv.load(Path.of(".env")) + System.getenv()
    exitProcess(Cli(settings, System.out, System.err).run(args.toList()))
}

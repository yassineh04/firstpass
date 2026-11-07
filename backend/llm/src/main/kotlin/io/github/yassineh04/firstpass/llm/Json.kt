package io.github.yassineh04.firstpass.llm

import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule

/** The JSON mapper of the llm adapter, with Kotlin support. Thread-safe once built. */
internal val JSON: JsonMapper = jsonMapper { addModule(kotlinModule()) }

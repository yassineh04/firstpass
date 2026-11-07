// Adapter: LLM providers behind LlmPort (Gemini, Groq, Ollama, fake).
plugins {
    id("firstpass.kotlin-jvm")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.json.schema.validator)
}

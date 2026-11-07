// Adapter: LLM providers behind LlmPort (Gemini, Groq, Ollama, fake).
plugins {
    id("firstpass.kotlin-jvm")
}

dependencies {
    implementation(project(":core"))
}

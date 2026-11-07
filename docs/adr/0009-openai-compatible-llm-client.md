# 0009. One OpenAI-compatible chat client for several LLM providers

- Status: Accepted
- Date: 2026-11-06

## Context

Reviews rely on free LLM tiers, whose limits and models change often. A review must keep working when one provider throttles or removes a model.

All the providers we plan to use document an OpenAI-compatible Chat Completions API:

- Gemini, at `https://generativelanguage.googleapis.com/v1beta/openai/`, with structured output; Google marks this compatibility as beta ([Gemini OpenAI compatibility](https://aistudio.google.com/docs/openai)).
- Groq, at `https://api.groq.com/openai/v1`, with strict JSON-schema structured outputs on some models such as `openai/gpt-oss-120b`, and a JSON object mode on others ([Groq OpenAI compatibility](https://console.groq.com/docs/openai), [structured outputs](https://console.groq.com/docs/structured-outputs)).
- Ollama, locally at `http://localhost:11434/v1/`, with a subset of the API including `response_format` ([Ollama OpenAI compatibility](https://docs.ollama.com/api/openai-compatibility)).

## Decision

- `core` defines an `LlmPort` that knows nothing about HTTP or providers.
- The `llm` module implements it with one HTTP adapter speaking Chat Completions, configured per provider: base URL, model, token budgets, and the structured-output mode the provider supports.
- A fake client implements the same port for tests.
- The provider is chosen by configuration (`auto`, `gemini` or `groq`), with automatic fallback on `429` and `5xx` errors.
- Whatever the provider returns, the server validates the output against our own JSON schema before using it.

## Alternatives considered

- **Each provider's own SDK.** Access to every feature, but more dependencies and code tied to one vendor.
- **An LLM framework such as LangChain4j.** Many abstractions we do not need, and a large dependency.

## Consequences

- Positive: changing provider is a configuration change; one adapter to test against recorded responses.
- Negative: we only use features common to all providers. If Gemini's beta compatibility breaks, a native Gemini adapter can be added behind the same port without touching `core`.

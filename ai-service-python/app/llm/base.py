"""Pluggable chat LLM abstraction (strategy pattern).

Mirrors ``app.embeddings``: one ABC, one factory, and a concrete
implementation per ``LLM_PROVIDER`` value.
"""

from abc import ABC, abstractmethod

from app.config import Settings


class LLMProvider(ABC):
    """Base class for chat-completion providers."""

    @abstractmethod
    def chat(
        self,
        system_prompt: str,
        user_prompt: str,
        model: str | None = None,
        temperature: float = 0.1,
    ) -> str:
        """Return the assistant's reply as plain text.

        ``model`` overrides the configured default for this single call.
        """
        raise NotImplementedError

    @property
    @abstractmethod
    def default_model(self) -> str:
        """Model identifier used when the caller does not pass ``model``."""
        raise NotImplementedError


def create_llm_provider(settings: Settings) -> LLMProvider:
    """Factory: build the provider matching ``settings.llm_provider``."""
    provider = (settings.llm_provider or "ollama").strip().lower()

    if provider == "ollama":
        from app.llm.ollama import OllamaLLMProvider

        return OllamaLLMProvider(settings)

    if provider == "openai_compatible":
        from app.llm.openai_compatible import OpenAICompatibleLLMProvider

        return OpenAICompatibleLLMProvider(settings)

    raise ValueError(
        f"Unsupported LLM_PROVIDER: '{settings.llm_provider}'. "
        "Expected one of: ollama, openai_compatible"
    )

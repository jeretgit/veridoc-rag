"""Pluggable embedding provider abstraction (strategy pattern)."""

from abc import ABC, abstractmethod

from app.config import Settings


class EmbeddingProvider(ABC):
    """Base class for all embedding providers."""

    @abstractmethod
    def embed(self, texts: list[str]) -> list[list[float]]:
        """Return one embedding vector per input text.

        All vectors must have the same dimension (768 for the default
        nomic-embed-text model).
        """
        raise NotImplementedError

    @property
    @abstractmethod
    def dimension(self) -> int:
        """Vector dimension produced by this provider."""
        raise NotImplementedError

    def embed_one(self, text: str) -> list[float]:
        """Convenience wrapper for a single text."""
        return self.embed([text])[0]


def create_embedding_provider(settings: Settings) -> EmbeddingProvider:
    """Factory: build the provider matching ``settings.llm_provider``."""
    provider = (settings.llm_provider or "ollama").strip().lower()

    if provider == "ollama":
        from app.embeddings.ollama import OllamaEmbeddingProvider

        return OllamaEmbeddingProvider(settings)

    if provider == "openai_compatible":
        from app.embeddings.openai_compatible import (
            OpenAICompatibleEmbeddingProvider,
        )

        return OpenAICompatibleEmbeddingProvider(settings)

    raise ValueError(
        f"Unsupported LLM_PROVIDER: '{settings.llm_provider}'. "
        "Expected one of: ollama, openai_compatible"
    )

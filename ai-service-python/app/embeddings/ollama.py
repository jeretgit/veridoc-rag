"""Ollama embedding provider.

Uses the modern ``POST /api/embed`` endpoint which accepts a batch of inputs
and returns ``{"embeddings": [[...], ...]}``. Falls back to the legacy
``/api/embeddings`` endpoint (single input) when the modern one is unavailable.
"""

import logging

import httpx

from app.config import Settings
from app.embeddings.base import EmbeddingProvider

logger = logging.getLogger(__name__)

# nomic-embed-text and most local embedding models emit 768-dim vectors.
DEFAULT_DIMENSION = 768


class OllamaEmbeddingProvider(EmbeddingProvider):
    def __init__(self, settings: Settings) -> None:
        self.base_url = (settings.ollama_base_url or "").rstrip("/")
        self.model = settings.embedding_model or "nomic-embed-text"
        self._dimension = DEFAULT_DIMENSION
        self._use_legacy_endpoint = False

    @property
    def dimension(self) -> int:
        return self._dimension

    def embed(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []

        if self._use_legacy_endpoint:
            return [self._embed_legacy(t) for t in texts]

        try:
            return self._embed_batch(texts)
        except httpx.HTTPStatusError as exc:
            # 404 means this Ollama version only has the legacy endpoint.
            if exc.response is not None and exc.response.status_code == 404:
                logger.warning(
                    "Ollama /api/embed unavailable, falling back to /api/embeddings"
                )
                self._use_legacy_endpoint = True
                return [self._embed_legacy(t) for t in texts]
            raise

    def _embed_batch(self, texts: list[str]) -> list[list[float]]:
        url = f"{self.base_url}/api/embed"
        payload = {"model": self.model, "input": texts}

        with httpx.Client(timeout=120.0) as client:
            response = client.post(url, json=payload)
            response.raise_for_status()
            data = response.json()

        embeddings = data.get("embeddings")
        if not embeddings or len(embeddings) != len(texts):
            raise ValueError(
                f"Unexpected Ollama embed response: got "
                f"{len(embeddings) if embeddings else 0} vectors "
                f"for {len(texts)} inputs"
            )

        self._dimension = len(embeddings[0])
        return [[float(x) for x in vec] for vec in embeddings]

    def _embed_legacy(self, text: str) -> list[float]:
        url = f"{self.base_url}/api/embeddings"
        payload = {"model": self.model, "prompt": text}

        with httpx.Client(timeout=120.0) as client:
            response = client.post(url, json=payload)
            response.raise_for_status()
            data = response.json()

        embedding = data.get("embedding")
        if not embedding:
            raise ValueError("Unexpected Ollama embeddings response: no vector")

        self._dimension = len(embedding)
        return [float(x) for x in embedding]

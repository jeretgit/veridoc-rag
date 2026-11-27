"""OpenAI-compatible embedding provider.

Calls ``POST {OPENAI_API_BASE}/embeddings`` — the wire format shared by
OpenAI, Azure OpenAI, vLLM, LM Studio, Together, and similar services.
Reserved for cloud/demo deployments; the local default stays Ollama.
"""

import httpx

from app.config import Settings
from app.embeddings.base import EmbeddingProvider

DEFAULT_DIMENSION = 768
DEFAULT_EMBEDDING_MODEL = "text-embedding-3-small"


class OpenAICompatibleEmbeddingProvider(EmbeddingProvider):
    def __init__(self, settings: Settings) -> None:
        self.base_url = (settings.openai_api_base or "").rstrip("/")
        self.api_key = settings.openai_api_key or ""
        self.model = (
            settings.openai_embedding_model
            or settings.openai_model
            or DEFAULT_EMBEDDING_MODEL
        )
        self._dimension = DEFAULT_DIMENSION

    @property
    def dimension(self) -> int:
        return self._dimension

    def embed(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        if not self.base_url:
            raise ValueError(
                "OPENAI_API_BASE is not configured but LLM_PROVIDER=openai_compatible"
            )

        url = f"{self.base_url}/embeddings"
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"

        payload = {"model": self.model, "input": texts}

        with httpx.Client(timeout=120.0) as client:
            response = client.post(url, json=payload, headers=headers)
            response.raise_for_status()
            data = response.json()

        items = data.get("data")
        if not items or len(items) != len(texts):
            raise ValueError(
                f"Unexpected embeddings response: got "
                f"{len(items) if items else 0} vectors for {len(texts)} inputs"
            )

        # Order by index — the API does not guarantee response ordering.
        items = sorted(items, key=lambda item: item.get("index", 0))
        vectors = [[float(x) for x in item["embedding"]] for item in items]

        self._dimension = len(vectors[0])
        return vectors

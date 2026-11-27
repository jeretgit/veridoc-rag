"""Ollama chat-completion provider (``POST /api/chat``, non-streaming)."""

import logging

import httpx

from app.config import Settings
from app.llm.base import LLMProvider

logger = logging.getLogger(__name__)


class OllamaLLMProvider(LLMProvider):
    def __init__(self, settings: Settings) -> None:
        self.base_url = (settings.ollama_base_url or "").rstrip("/")
        self._default_model = settings.chat_model or "llama3.1:8b"

    @property
    def default_model(self) -> str:
        return self._default_model

    def chat(
        self,
        system_prompt: str,
        user_prompt: str,
        model: str | None = None,
        temperature: float = 0.1,
    ) -> str:
        used_model = model or self._default_model
        url = f"{self.base_url}/api/chat"

        payload = {
            "model": used_model,
            "stream": False,
            # Low temperature: this is a grounded extraction task, not a
            # creative one — we want the model to stay close to the context.
            "options": {"temperature": temperature},
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
        }

        logger.debug("Calling Ollama chat model %s", used_model)

        with httpx.Client(timeout=180.0) as client:
            response = client.post(url, json=payload)
            response.raise_for_status()
            data = response.json()

        message = data.get("message") or {}
        content = message.get("content")
        if not content:
            raise ValueError(
                "Ollama chat returned an empty message. "
                f"Raw response keys: {list(data.keys())}"
            )
        return content.strip()

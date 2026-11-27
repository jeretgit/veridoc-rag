"""OpenAI-compatible chat provider (``POST /chat/completions``).

Reserved for cloud/demo deployments; the local default remains Ollama.
"""

import httpx

from app.config import Settings
from app.llm.base import LLMProvider

DEFAULT_CHAT_MODEL = "gpt-4o-mini"


class OpenAICompatibleLLMProvider(LLMProvider):
    def __init__(self, settings: Settings) -> None:
        self.base_url = (settings.openai_api_base or "").rstrip("/")
        self.api_key = settings.openai_api_key or ""
        self._default_model = settings.openai_model or DEFAULT_CHAT_MODEL

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
        if not self.base_url:
            raise ValueError(
                "OPENAI_API_BASE is not configured but LLM_PROVIDER=openai_compatible"
            )

        used_model = model or self._default_model
        url = f"{self.base_url}/chat/completions"

        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"

        payload = {
            "model": used_model,
            "temperature": temperature,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
        }

        with httpx.Client(timeout=180.0) as client:
            response = client.post(url, json=payload, headers=headers)
            response.raise_for_status()
            data = response.json()

        choices = data.get("choices") or []
        if not choices:
            raise ValueError("Chat completions response contained no choices")

        content = (choices[0].get("message") or {}).get("content")
        if not content:
            raise ValueError("Chat completions response contained an empty message")
        return content.strip()

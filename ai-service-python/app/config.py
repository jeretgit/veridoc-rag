from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # ---- LLM provider selection ----
    llm_provider: str = "ollama"

    # ---- Ollama ----
    ollama_base_url: str = "http://host.docker.internal:11434"
    embedding_model: str = "nomic-embed-text"
    chat_model: str = "llama3.1:8b"

    # ---- Retrieval ----
    top_k: int = 5
    min_similarity_score: float = 0.35

    # ---- OpenAI-compatible (reserved for cloud demo mode) ----
    openai_api_base: str = ""
    openai_api_key: str = ""
    openai_model: str = ""
    openai_embedding_model: str = ""

    # ---- Database ----
    database_url: str = "postgresql://veridoc:veridoc123@postgres:5432/veridoc"

    # ---- Chunking ----
    chunk_size: int = 600
    chunk_overlap: int = 100

    # ---- Storage ----
    upload_dir: str = "/app/uploads"

    model_config = {"env_file": ".env", "extra": "ignore"}


settings = Settings()

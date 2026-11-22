from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    llm_provider: str = "ollama"
    ollama_base_url: str = "http://host.docker.internal:11434"
    openai_api_base: str = ""
    openai_api_key: str = ""
    openai_model: str = ""
    database_url: str = ""

    model_config = {"env_file": ".env", "extra": "ignore"}


settings = Settings()

from fastapi import FastAPI

from app.config import settings

app = FastAPI(title="veridoc AI Service", version="0.0.1")


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "llm_provider": settings.llm_provider,
    }

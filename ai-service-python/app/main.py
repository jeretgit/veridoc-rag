import logging

from fastapi import FastAPI

from app.api import ingest_router
from app.config import settings

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)

app = FastAPI(title="veridoc AI Service", version="0.1.0")

app.include_router(ingest_router)


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "llm_provider": settings.llm_provider,
    }

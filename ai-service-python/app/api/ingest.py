"""Internal ingestion endpoint: PDF -> chunks -> embeddings -> pgvector."""

from __future__ import annotations

import logging
import uuid
from pathlib import Path

from fastapi import APIRouter, HTTPException, status
from pydantic import BaseModel, Field

from app.config import settings
from app.db.postgres import (
    delete_chunks_for_document,
    get_connection,
    insert_chunks,
)
from app.embeddings import create_embedding_provider
from app.services.pdf_chunker import chunk_pdf

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/internal", tags=["internal"])


class IngestRequest(BaseModel):
    document_id: uuid.UUID
    file_path: str = Field(..., min_length=1)
    chunk_size: int | None = Field(default=None, gt=0)
    chunk_overlap: int | None = Field(default=None, ge=0)


class IngestResponse(BaseModel):
    document_id: str
    page_count: int
    chunk_count: int


@router.post("/ingest", response_model=IngestResponse)
def ingest(request: IngestRequest) -> IngestResponse:
    """Parse a PDF, chunk it, embed every chunk, and persist to pgvector.

    Idempotent: previously stored chunks for this document are removed inside
    the same transaction, so a retry never duplicates rows.
    """
    document_id = str(request.document_id)
    path = Path(request.file_path)

    # ---- 1. validate the file ----
    if not path.is_file():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"PDF file not found at path: {request.file_path}",
        )

    chunk_size = request.chunk_size or settings.chunk_size
    chunk_overlap = (
        request.chunk_overlap
        if request.chunk_overlap is not None
        else settings.chunk_overlap
    )

    if chunk_overlap >= chunk_size:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=(
                f"chunk_overlap ({chunk_overlap}) must be smaller than "
                f"chunk_size ({chunk_size})"
            ),
        )

    # ---- 2. parse + chunk ----
    try:
        result = chunk_pdf(path, chunk_size=chunk_size, chunk_overlap=chunk_overlap)
    except FileNotFoundError as exc:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)
        ) from exc
    except Exception as exc:
        logger.exception("Failed to parse PDF %s", path)
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Failed to parse PDF: {exc}",
        ) from exc

    if not result.has_text:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "No extractable text found in this PDF. It is likely a scanned "
                "image-only document; OCR support is required before ingestion."
            ),
        )

    logger.info(
        "Document %s: %d pages, %d chunks (chunk_size=%d, overlap=%d)",
        document_id,
        result.page_count,
        result.chunk_count,
        chunk_size,
        chunk_overlap,
    )

    # ---- 3. embed ----
    try:
        provider = create_embedding_provider(settings)
        embeddings = provider.embed([chunk.content for chunk in result.chunks])
    except Exception as exc:
        logger.exception("Embedding generation failed for document %s", document_id)
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"Embedding generation failed: {exc}",
        ) from exc

    if len(embeddings) != len(result.chunks):
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=(
                f"Embedding count mismatch: {len(embeddings)} vectors for "
                f"{len(result.chunks)} chunks"
            ),
        )

    # ---- 4. persist atomically (delete old + insert new) ----
    try:
        with get_connection() as conn:
            with conn.transaction():
                removed = delete_chunks_for_document(conn, document_id)
                if removed:
                    logger.info(
                        "Removed %d stale chunks for document %s", removed, document_id
                    )
                insert_chunks(conn, document_id, result.chunks, embeddings)
    except HTTPException:
        raise
    except Exception as exc:
        logger.exception("Failed to persist chunks for document %s", document_id)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to persist chunks: {exc}",
        ) from exc

    return IngestResponse(
        document_id=document_id,
        page_count=result.page_count,
        chunk_count=result.chunk_count,
    )

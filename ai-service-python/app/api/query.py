"""Internal RAG query endpoint."""

from __future__ import annotations

import logging
import uuid

from fastapi import APIRouter, HTTPException, status
from pydantic import BaseModel, Field, field_validator

from app.services.rag_service import answer_question

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/internal", tags=["internal"])


class QueryRequest(BaseModel):
    question: str = Field(..., min_length=1)
    document_ids: list[uuid.UUID] | None = None
    top_k: int | None = Field(default=None, gt=0, le=50)
    min_score: float | None = Field(default=None, ge=-1.0, le=1.0)
    model: str | None = None

    @field_validator("question")
    @classmethod
    def question_must_not_be_blank(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("question must not be blank")
        return value.strip()


class CitationResponse(BaseModel):
    document_id: str
    file_name: str
    page_number: int
    chunk_index: int
    score: float
    snippet: str


class QueryResponse(BaseModel):
    answer: str
    grounded: bool
    model_used: str
    citations: list[CitationResponse]


@router.post("/query", response_model=QueryResponse)
def query(request: QueryRequest) -> QueryResponse:
    """Answer a question strictly from the ingested document chunks."""
    document_ids = (
        [str(doc_id) for doc_id in request.document_ids]
        if request.document_ids
        else None
    )

    try:
        result = answer_question(
            question=request.question,
            document_ids=document_ids,
            top_k=request.top_k,
            min_score=request.min_score,
            model=request.model,
        )
    except ValueError as exc:
        # Bad configuration / unsupported provider
        logger.warning("Query rejected: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)
        ) from exc
    except Exception as exc:
        logger.exception("Query failed")
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"Query pipeline failed: {exc}",
        ) from exc

    return QueryResponse(**result.to_dict())

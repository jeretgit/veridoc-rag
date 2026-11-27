"""Grounded retrieval-augmented question answering.

The pipeline is deliberately conservative:

1. Embed the question and retrieve the top-k most similar chunks from
   documents whose status is READY.
2. If nothing clears ``min_score``, short-circuit with a refusal — the LLM is
   never called, so it cannot invent an answer from thin air.
3. Otherwise build a numbered context block ([1], [2], ...) and ask the model
   to answer *only* from that context, citing ``[n]`` markers, and to say so
   explicitly when the context is insufficient.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field

from app.config import Settings, settings as default_settings
from app.db.postgres import get_connection, search_chunks
from app.embeddings import create_embedding_provider
from app.llm import create_llm_provider

logger = logging.getLogger(__name__)

REFUSAL_ANSWER = (
    "I could not find relevant information in the uploaded documents "
    "to answer this question."
)

SYSTEM_PROMPT = """You are a precise document question-answering assistant.

Rules you must follow:
1. Answer ONLY using the numbered context passages provided by the user.
2. Cite every factual statement with the bracketed number of its source
   passage, e.g. "The policy takes effect in June [2]."
3. If the provided context does not contain enough information to answer,
   say so plainly instead of guessing. Never invent facts, figures, names,
   or citations that are not in the context.
4. Answer in the SAME language the user asked the question in.
5. Be concise and do not repeat the context verbatim beyond what is needed."""


@dataclass
class Citation:
    document_id: str
    file_name: str
    page_number: int
    chunk_index: int
    score: float
    snippet: str

    def to_dict(self) -> dict:
        return {
            "document_id": self.document_id,
            "file_name": self.file_name,
            "page_number": self.page_number,
            "chunk_index": self.chunk_index,
            "score": round(self.score, 4),
            "snippet": self.snippet,
        }


@dataclass
class RagResult:
    answer: str
    grounded: bool
    model_used: str
    citations: list[Citation] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "answer": self.answer,
            "grounded": self.grounded,
            "model_used": self.model_used,
            "citations": [c.to_dict() for c in self.citations],
        }


def build_context_block(matches: list[dict]) -> str:
    """Render retrieved chunks as a numbered context block."""
    lines: list[str] = []
    for index, match in enumerate(matches, start=1):
        lines.append(
            f"[{index}] (file: {match['file_name']}, page {match['page_number']})\n"
            f"{match['content']}"
        )
    return "\n\n".join(lines)


def build_user_prompt(question: str, matches: list[dict]) -> str:
    """Assemble the grounded user prompt: context first, then the question."""
    context = build_context_block(matches)
    return (
        f"Context passages:\n\n{context}\n\n"
        f"---\n\n"
        f"Question: {question}\n\n"
        f"Answer using only the context above and cite sources as [1], [2], etc. "
        f"If the context is insufficient, state that clearly."
    )


def answer_question(
    question: str,
    document_ids: list[str] | None = None,
    top_k: int | None = None,
    min_score: float | None = None,
    model: str | None = None,
    settings: Settings | None = None,
) -> RagResult:
    """Run the full retrieve -> ground -> generate pipeline."""
    cfg = settings or default_settings

    effective_top_k = top_k if top_k is not None else cfg.top_k
    effective_min_score = min_score if min_score is not None else cfg.min_similarity_score

    # ---- 1. embed the question ----
    embedding_provider = create_embedding_provider(cfg)
    query_vector = embedding_provider.embed_one(question)

    # ---- 2. retrieve ----
    with get_connection() as conn:
        matches = search_chunks(
            conn,
            query_vector=query_vector,
            top_k=effective_top_k,
            min_score=effective_min_score,
            document_ids=document_ids,
        )

    logger.info(
        "Retrieved %d chunk(s) for question (top_k=%d, min_score=%.2f)",
        len(matches),
        effective_top_k,
        effective_min_score,
    )

    # ---- 3. anti-hallucination short-circuit ----
    if not matches:
        logger.info("No chunk cleared min_score; refusing without calling the LLM")
        return RagResult(
            answer=REFUSAL_ANSWER,
            grounded=False,
            model_used=model or create_llm_provider(cfg).default_model,
            citations=[],
        )

    # ---- 4. generate ----
    llm = create_llm_provider(cfg)
    used_model = model or llm.default_model

    user_prompt = build_user_prompt(question, matches)
    answer_text = llm.chat(
        system_prompt=SYSTEM_PROMPT,
        user_prompt=user_prompt,
        model=model,
        temperature=0.1,
    )

    citations = [
        Citation(
            document_id=match["document_id"],
            file_name=match["file_name"],
            page_number=match["page_number"],
            chunk_index=match["chunk_index"],
            score=match["score"],
            snippet=match["content"],
        )
        for match in matches
    ]

    return RagResult(
        answer=answer_text,
        grounded=True,
        model_used=used_model,
        citations=citations,
    )

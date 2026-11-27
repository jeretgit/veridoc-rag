"""Unit tests for the grounded RAG pipeline.

The LLM and the vector search are both mocked, so these tests exercise the
control flow (short-circuit refusal vs. grounded generation) without needing
Postgres or Ollama.
"""

from unittest.mock import MagicMock, patch

import pytest

from app.services import rag_service
from app.services.rag_service import (
    REFUSAL_ANSWER,
    SYSTEM_PROMPT,
    answer_question,
    build_context_block,
    build_user_prompt,
)


SAMPLE_MATCHES = [
    {
        "document_id": "11111111-1111-1111-1111-111111111111",
        "file_name": "handbook.pdf",
        "page_number": 3,
        "chunk_index": 7,
        "content": "Employees receive 25 days of paid annual leave per year.",
        "score": 0.81234567,
    },
    {
        "document_id": "22222222-2222-2222-2222-222222222222",
        "file_name": "policy.pdf",
        "page_number": 1,
        "chunk_index": 0,
        "content": "Leave requests must be filed at least two weeks in advance.",
        "score": 0.65432198,
    },
]


@pytest.fixture
def fake_embedding_provider():
    provider = MagicMock()
    provider.embed_one.return_value = [0.01] * 768
    return provider


@pytest.fixture
def fake_llm():
    llm = MagicMock()
    llm.default_model = "llama3.1:8b"
    llm.chat.return_value = "You get 25 days of annual leave [1]."
    return llm


# --------------------------------------------------------------------------
# Anti-hallucination short circuit
# --------------------------------------------------------------------------

def test_no_matches_refuses_without_calling_llm(fake_embedding_provider, fake_llm):
    """Empty retrieval must short-circuit: no LLM call, grounded=false."""
    with patch.object(rag_service, "create_embedding_provider",
                      return_value=fake_embedding_provider), \
         patch.object(rag_service, "search_chunks", return_value=[]), \
         patch.object(rag_service, "get_connection"), \
         patch.object(rag_service, "create_llm_provider", return_value=fake_llm):

        result = answer_question("How many vacation days do I get?")

    assert result.grounded is False
    assert result.answer == REFUSAL_ANSWER
    assert result.citations == []
    # The model must never be invoked when nothing was retrieved.
    fake_llm.chat.assert_not_called()


def test_low_similarity_matches_below_threshold_are_treated_as_no_hits(
    fake_embedding_provider, fake_llm
):
    """search_chunks already filters by min_score; an empty list is a refusal."""
    with patch.object(rag_service, "create_embedding_provider",
                      return_value=fake_embedding_provider), \
         patch.object(rag_service, "search_chunks", return_value=[]) as search, \
         patch.object(rag_service, "get_connection"), \
         patch.object(rag_service, "create_llm_provider", return_value=fake_llm):

        result = answer_question("What is the capital of France?", min_score=0.9)

    assert result.grounded is False
    search.assert_called_once()
    assert search.call_args.kwargs["min_score"] == 0.9
    fake_llm.chat.assert_not_called()


# --------------------------------------------------------------------------
# Grounded generation
# --------------------------------------------------------------------------

def test_matches_produce_grounded_answer_with_citations(
    fake_embedding_provider, fake_llm
):
    with patch.object(rag_service, "create_embedding_provider",
                      return_value=fake_embedding_provider), \
         patch.object(rag_service, "search_chunks", return_value=SAMPLE_MATCHES), \
         patch.object(rag_service, "get_connection"), \
         patch.object(rag_service, "create_llm_provider", return_value=fake_llm):

        result = answer_question("How many annual leave days do I get?")

    assert result.grounded is True
    assert result.model_used == "llama3.1:8b"
    assert len(result.citations) == 2

    first = result.citations[0].to_dict()
    assert first["document_id"] == SAMPLE_MATCHES[0]["document_id"]
    assert first["file_name"] == "handbook.pdf"
    assert first["page_number"] == 3
    assert first["chunk_index"] == 7
    assert first["score"] == 0.8123           # rounded to 4 dp
    assert "25 days" in first["snippet"]      # original chunk text

    second = result.citations[1].to_dict()
    assert second["page_number"] == 1
    assert second["score"] == 0.6543


def test_grounding_prompt_contains_numbered_context_and_question(
    fake_embedding_provider, fake_llm
):
    with patch.object(rag_service, "create_embedding_provider",
                      return_value=fake_embedding_provider), \
         patch.object(rag_service, "search_chunks", return_value=SAMPLE_MATCHES), \
         patch.object(rag_service, "get_connection"), \
         patch.object(rag_service, "create_llm_provider", return_value=fake_llm):

        answer_question("How many annual leave days do I get?")

    fake_llm.chat.assert_called_once()
    kwargs = fake_llm.chat.call_args.kwargs

    # The strict grounding system prompt is passed through unchanged.
    assert kwargs["system_prompt"] == SYSTEM_PROMPT
    assert "ONLY using the numbered context" in kwargs["system_prompt"]
    assert "cite" in kwargs["system_prompt"].lower()

    user_prompt = kwargs["user_prompt"]
    # Passages are numbered [1] and [2] and carry their file/page metadata.
    assert "[1] (file: handbook.pdf, page 3)" in user_prompt
    assert "[2] (file: policy.pdf, page 1)" in user_prompt
    assert "Employees receive 25 days" in user_prompt
    # The question is appended after the context.
    assert "How many annual leave days do I get?" in user_prompt
    assert user_prompt.index("[1]") < user_prompt.index("Question:")
    # Low temperature is requested to reduce hallucination.
    assert kwargs["temperature"] == pytest.approx(0.1)


def test_custom_model_override_is_forwarded(fake_embedding_provider, fake_llm):
    with patch.object(rag_service, "create_embedding_provider",
                      return_value=fake_embedding_provider), \
         patch.object(rag_service, "search_chunks", return_value=SAMPLE_MATCHES), \
         patch.object(rag_service, "get_connection"), \
         patch.object(rag_service, "create_llm_provider", return_value=fake_llm):

        result = answer_question("How many annual leave days?", model="qwen2.5:7b")

    assert fake_llm.chat.call_args.kwargs["model"] == "qwen2.5:7b"
    assert result.model_used == "qwen2.5:7b"


def test_document_ids_filter_is_passed_to_search(fake_embedding_provider, fake_llm):
    doc_ids = ["11111111-1111-1111-1111-111111111111"]

    with patch.object(rag_service, "create_embedding_provider",
                      return_value=fake_embedding_provider), \
         patch.object(rag_service, "search_chunks", return_value=SAMPLE_MATCHES) as search, \
         patch.object(rag_service, "get_connection"), \
         patch.object(rag_service, "create_llm_provider", return_value=fake_llm):

        answer_question("question", document_ids=doc_ids)

    assert search.call_args.kwargs["document_ids"] == doc_ids


def test_top_k_and_min_score_defaults_come_from_settings(
    fake_embedding_provider, fake_llm
):
    with patch.object(rag_service, "create_embedding_provider",
                      return_value=fake_embedding_provider), \
         patch.object(rag_service, "search_chunks", return_value=SAMPLE_MATCHES) as search, \
         patch.object(rag_service, "get_connection"), \
         patch.object(rag_service, "create_llm_provider", return_value=fake_llm):

        answer_question("question")

    assert search.call_args.kwargs["top_k"] == 5
    assert search.call_args.kwargs["min_score"] == pytest.approx(0.35)


# --------------------------------------------------------------------------
# Prompt building helpers
# --------------------------------------------------------------------------

def test_build_context_block_numbers_passages_from_one():
    block = build_context_block(SAMPLE_MATCHES)
    assert block.startswith("[1] (file: handbook.pdf, page 3)")
    assert block.count("[1]") == 1
    assert "[2] (file: policy.pdf, page 1)" in block


def test_build_user_prompt_places_question_after_context():
    prompt = build_user_prompt("Q?", SAMPLE_MATCHES)
    assert prompt.index("Context passages:") < prompt.index("[1]")
    assert prompt.index("[2]") < prompt.index("Question: Q?")

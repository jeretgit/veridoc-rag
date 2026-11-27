"""Unit tests for page-aware PDF chunking."""

from app.services.pdf_chunker import (
    Chunk,
    chunk_pdf,
    chunk_text,
    normalize_text,
)


# --------------------------------------------------------------------------
# chunk_text — pure window logic
# --------------------------------------------------------------------------

def test_chunk_text_returns_single_chunk_when_text_fits():
    text = "short text"
    assert chunk_text(text, chunk_size=600, chunk_overlap=100) == ["short text"]


def test_chunk_text_splits_with_overlap():
    # 250 chars, window 100, overlap 20 -> step 80
    # windows start at 0, 80, 160 -> 3 chunks (last one is partial)
    text = "a" * 250
    pieces = chunk_text(text, chunk_size=100, chunk_overlap=20)

    assert len(pieces) == 3
    assert len(pieces[0]) == 100
    assert len(pieces[1]) == 100
    assert len(pieces[2]) == 90  # 250 - 160
    assert pieces[0] == "a" * 100


def test_chunk_text_overlap_is_real_substring_of_previous_window():
    text = "0123456789" * 20  # 200 chars
    pieces = chunk_text(text, chunk_size=50, chunk_overlap=10)

    step = 50 - 10
    for i in range(1, len(pieces)):
        prev = pieces[i - 1]
        # The overlap region must match the tail of the previous chunk.
        assert prev[-10:] == text[(i * step) - 10 : i * step]


def test_chunk_text_rejects_overlap_ge_size():
    import pytest

    with pytest.raises(ValueError, match="smaller than chunk_size"):
        chunk_text("abc" * 100, chunk_size=50, chunk_overlap=50)


def test_chunk_text_empty_input_returns_no_chunks():
    assert chunk_text("   \n  ", chunk_size=100, chunk_overlap=10) == []


# --------------------------------------------------------------------------
# normalize_text — whitespace cleanup
# --------------------------------------------------------------------------

def test_normalize_text_collapses_horizontal_whitespace():
    raw = "Hello    world\t\tagain\u00a0\u00a0end"
    assert normalize_text(raw) == "Hello world again end"


def test_normalize_text_collapses_excessive_blank_lines():
    raw = "First\n\n\n\n\nSecond"
    assert normalize_text(raw) == "First\n\nSecond"


def test_normalize_text_handles_windows_line_endings():
    assert normalize_text("a\r\nb\rc") == "a\nb\nc"


# --------------------------------------------------------------------------
# chunk_pdf — page awareness across a real PDF
# --------------------------------------------------------------------------

def test_chunk_pdf_preserves_1based_page_numbers(make_pdf_file):
    pdf = make_pdf_file(["PAGE ONE CONTENT", "PAGE TWO CONTENT"])

    result = chunk_pdf(pdf, chunk_size=600, chunk_overlap=100)

    assert result.page_count == 2
    assert result.has_text is True
    assert len(result.chunks) == 2

    # Page numbers are 1-based and follow document order.
    assert result.chunks[0].page_number == 1
    assert result.chunks[1].page_number == 2
    assert "PAGE ONE" in result.chunks[0].content.upper()
    assert "PAGE TWO" in result.chunks[1].content.upper()


def test_chunk_pdf_chunk_index_is_globally_increasing(make_pdf_file):
    long_text = "Lorem ipsum dolor sit amet. " * 40  # forces multiple chunks
    pdf = make_pdf_file([long_text, long_text, "tail page"])

    result = chunk_pdf(pdf, chunk_size=100, chunk_overlap=20)

    assert len(result.chunks) > 3
    indices = [c.chunk_index for c in result.chunks]
    assert indices == list(range(len(result.chunks)))  # 0,1,2,... no gaps
    # Chunks are ordered by page, so page numbers never decrease.
    pages = [c.page_number for c in result.chunks]
    assert pages == sorted(pages)


def test_chunk_pdf_skips_blank_pages(make_pdf_file):
    # Page 2 is intentionally empty (no text inserted).
    pdf = make_pdf_file(["page one text", "", "page three text"])

    result = chunk_pdf(pdf, chunk_size=600, chunk_overlap=100)

    assert result.page_count == 3           # the blank page still counts
    assert result.pages_with_text == 2      # but it is not chunked
    assert [c.page_number for c in result.chunks] == [1, 3]


def test_chunk_pdf_returns_empty_for_image_only_pdf(make_pdf_file):
    # A PDF with no text at all (simulates a scan without an OCR layer).
    pdf = make_pdf_file(["", "", ""])

    result = chunk_pdf(pdf, chunk_size=600, chunk_overlap=100)

    assert result.page_count == 3
    assert result.has_text is False
    assert result.chunks == []


def test_chunk_pdf_raises_for_missing_file(tmp_path):
    import pytest

    with pytest.raises(FileNotFoundError):
        chunk_pdf(tmp_path / "does-not-exist.pdf")


# --------------------------------------------------------------------------
# Chunk dataclass validation
# --------------------------------------------------------------------------

def test_chunk_rejects_zero_page_number():
    import pytest

    with pytest.raises(ValueError, match="1-based"):
        Chunk(page_number=0, chunk_index=0, content="x")

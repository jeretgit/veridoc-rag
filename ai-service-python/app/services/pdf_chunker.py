"""Page-aware PDF text extraction and fixed-window chunking.

Uses PyMuPDF (``fitz``) to walk the document page by page. Every chunk keeps
the 1-based ``page_number`` it came from, plus a globally increasing
``chunk_index``. Chunks never span pages, which keeps citations precise.
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from pathlib import Path

import fitz  # PyMuPDF

logger = logging.getLogger(__name__)

# Chunk defaults mirror the values in app.config.Settings.
DEFAULT_CHUNK_SIZE = 600
DEFAULT_CHUNK_OVERLAP = 100

_WHITESPACE_RE = re.compile(r"[ \t\u00a0\u2000-\u200a\u3000]+")
_BLANK_LINES_RE = re.compile(r"\n{3,}")


@dataclass
class Chunk:
    """A single text chunk with its provenance."""

    page_number: int          # 1-based page number
    chunk_index: int          # global, monotonically increasing, 0-based
    content: str

    def __post_init__(self) -> None:
        if self.page_number < 1:
            raise ValueError("page_number must be 1-based (>= 1)")
        if self.chunk_index < 0:
            raise ValueError("chunk_index must be >= 0")


@dataclass
class ChunkingResult:
    chunks: list[Chunk] = field(default_factory=list)
    page_count: int = 0
    pages_with_text: int = 0

    @property
    def chunk_count(self) -> int:
        return len(self.chunks)

    @property
    def has_text(self) -> bool:
        return bool(self.chunks)


def normalize_text(raw: str) -> str:
    """Collapse horizontal whitespace and excessive blank lines."""
    if not raw:
        return ""
    text = raw.replace("\r\n", "\n").replace("\r", "\n")
    text = _WHITESPACE_RE.sub(" ", text)
    # Drop trailing spaces on each line, keep intentional line breaks.
    text = "\n".join(line.strip() for line in text.split("\n"))
    text = _BLANK_LINES_RE.sub("\n\n", text)
    return text.strip()


def chunk_text(
    text: str,
    chunk_size: int = DEFAULT_CHUNK_SIZE,
    chunk_overlap: int = DEFAULT_CHUNK_OVERLAP,
) -> list[str]:
    """Split a single page's text into overlapping fixed-size windows.

    ``chunk_size`` counts characters, not tokens. ``chunk_overlap`` characters
    are repeated at the start of the next window so no sentence is cut in half
    without context on either side.
    """
    if chunk_size <= 0:
        raise ValueError("chunk_size must be > 0")
    if chunk_overlap < 0:
        raise ValueError("chunk_overlap must be >= 0")
    if chunk_overlap >= chunk_size:
        raise ValueError("chunk_overlap must be smaller than chunk_size")

    cleaned = text.strip()
    if not cleaned:
        return []

    if len(cleaned) <= chunk_size:
        return [cleaned]

    step = chunk_size - chunk_overlap
    pieces: list[str] = []
    start = 0
    text_len = len(cleaned)

    while start < text_len:
        end = min(start + chunk_size, text_len)
        piece = cleaned[start:end].strip()
        if piece:
            pieces.append(piece)
        if end >= text_len:
            break
        start += step

    return pieces


def extract_pages(path: str | Path) -> list[tuple[int, str]]:
    """Extract normalized text per page. Blank pages are omitted.

    Returns a list of ``(page_number, text)`` with 1-based page numbers.
    """
    pdf_path = Path(path)
    if not pdf_path.exists():
        raise FileNotFoundError(f"PDF not found: {pdf_path}")

    pages: list[tuple[int, str]] = []
    with fitz.open(pdf_path) as doc:
        for page_index, page in enumerate(doc):
            try:
                raw = page.get_text("text")
            except Exception as exc:  # a corrupt page shouldn't kill the doc
                logger.warning(
                    "Failed to extract text from page %d: %s", page_index + 1, exc
                )
                continue

            text = normalize_text(raw)
            if not text:
                # Scanned/image-only page — nothing usable here.
                logger.debug("Skipping blank page %d", page_index + 1)
                continue
            pages.append((page_index + 1, text))

    return pages


def chunk_pdf(
    path: str | Path,
    chunk_size: int = DEFAULT_CHUNK_SIZE,
    chunk_overlap: int = DEFAULT_CHUNK_OVERLAP,
) -> ChunkingResult:
    """Extract and chunk a PDF, preserving page provenance.

    The returned ``ChunkingResult`` has an empty ``chunks`` list when the PDF
    contains no extractable text (e.g. a pure image scan).
    """
    pdf_path = Path(path)
    if not pdf_path.exists():
        raise FileNotFoundError(f"PDF not found: {pdf_path}")

    result = ChunkingResult()

    with fitz.open(pdf_path) as doc:
        result.page_count = doc.page_count

        global_index = 0
        for page_index, page in enumerate(doc):
            page_number = page_index + 1
            try:
                raw = page.get_text("text")
            except Exception as exc:
                logger.warning(
                    "Failed to extract text from page %d: %s", page_number, exc
                )
                continue

            text = normalize_text(raw)
            if not text:
                logger.debug("Skipping blank page %d", page_number)
                continue

            result.pages_with_text += 1
            for piece in chunk_text(text, chunk_size, chunk_overlap):
                result.chunks.append(
                    Chunk(
                        page_number=page_number,
                        chunk_index=global_index,
                        content=piece,
                    )
                )
                global_index += 1

    return result

"""Shared pytest fixtures: helpers to build real PDFs on the fly with PyMuPDF."""

from pathlib import Path

import fitz
import pytest


def make_pdf(path: Path, pages: list[str]) -> Path:
    """"Write a real PDF whose page N contains ``pages[N-1]``."""
    doc = fitz.open()
    for text in pages:
        page = doc.new_page(width=595, height=842)  # A4 points
        if text:
            page.insert_text((72, 72), text, fontsize=11)
    doc.save(path)
    doc.close()
    return path


@pytest.fixture
def make_pdf_file(tmp_path):
    """Return a factory that writes a PDF into tmp_path."""

    def _factory(pages: list[str], name: str = "sample.pdf") -> Path:
        return make_pdf(tmp_path / name, pages)

    return _factory

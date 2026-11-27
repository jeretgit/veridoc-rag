"""Database helpers backed by psycopg 3 + pgvector."""

from __future__ import annotations

import logging
import uuid
from contextlib import contextmanager
from typing import Iterator

import psycopg
from pgvector.psycopg import register_vector

from app.config import settings

logger = logging.getLogger(__name__)


@contextmanager
def get_connection() -> Iterator[psycopg.Connection]:
    """Open a connection with the pgvector type adapter registered.

    ``register_vector`` teaches psycopg how to adapt ``list[float]`` to the
    ``vector`` column type on INSERT and how to read it back on SELECT.
    """
    url = settings.database_url
    if not url:
        raise RuntimeError("DATABASE_URL is not configured")

    with psycopg.connect(url) as conn:
        register_vector(conn)
        yield conn


def delete_chunks_for_document(conn: psycopg.Connection, document_id: str) -> int:
    """Delete every existing chunk for a document. Returns rows removed."""
    with conn.cursor() as cur:
        cur.execute(
            "DELETE FROM document_chunks WHERE document_id = %s",
            (document_id,),
        )
        return cur.rowcount


def insert_chunks(
    conn: psycopg.Connection,
    document_id: str,
    chunks: list,
    embeddings: list[list[float]],
) -> int:
    """Bulk insert chunks with their embedding vectors.

    ``chunks`` are ``pdf_chunker.Chunk`` instances. Returns rows inserted.
    """
    if not chunks:
        return 0
    if len(chunks) != len(embeddings):
        raise ValueError("chunks and embeddings must have the same length")

    params = [
        (
            str(uuid.uuid4()),
            document_id,
            chunk.page_number,
            chunk.chunk_index,
            chunk.content,
            embedding,
        )
        for chunk, embedding in zip(chunks, embeddings, strict=True)
    ]

    with conn.cursor() as cur:
        cur.executemany(
            """
            INSERT INTO document_chunks
                (id, document_id, page_number, chunk_index, content, embedding)
            VALUES (%s, %s, %s, %s, %s, %s)
            """,
            params,
        )
        return cur.rowcount

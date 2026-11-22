-- V1__init_documents_and_chunks.sql
-- veridoc-rag: document storage and chunk schema

CREATE EXTENSION IF NOT EXISTS vector;

-- ===== documents =====
CREATE TABLE documents (
    id            UUID PRIMARY KEY,
    file_name     VARCHAR(255) NOT NULL,
    stored_path   VARCHAR(512) NOT NULL,
    file_size     BIGINT       NOT NULL,
    content_type  VARCHAR(100) NOT NULL,
    status        VARCHAR(32)  NOT NULL DEFAULT 'UPLOADED',
    page_count    INT          DEFAULT 0,
    chunk_count   INT          DEFAULT 0,
    error_message TEXT,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- ===== document_chunks =====
CREATE TABLE document_chunks (
    id           UUID PRIMARY KEY,
    document_id  UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    page_number  INT  NOT NULL,
    chunk_index  INT  NOT NULL,
    content      TEXT NOT NULL,
    embedding    vector(768),
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

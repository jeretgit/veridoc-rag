package com.veridoc.document.client;

/**
 * Raised when the Python AI service cannot ingest a document.
 */
public class AiIngestException extends RuntimeException {

    public AiIngestException(String message) {
        super(message);
    }

    public AiIngestException(String message, Throwable cause) {
        super(message, cause);
    }
}

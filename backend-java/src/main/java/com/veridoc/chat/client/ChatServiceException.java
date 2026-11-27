package com.veridoc.chat.client;

/**
 * Raised when the Python AI service cannot answer a question.
 */
public class ChatServiceException extends RuntimeException {

    public ChatServiceException(String message) {
        super(message);
    }

    public ChatServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}

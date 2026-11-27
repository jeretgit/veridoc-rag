package com.veridoc.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.veridoc.chat.client.ChatAiClient;
import com.veridoc.chat.dto.ChatRequest;
import com.veridoc.chat.dto.ChatResponse;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatAiClient chatAiClient;

    public ChatService(ChatAiClient chatAiClient) {
        this.chatAiClient = chatAiClient;
    }

    /**
     * Answer a question against the ingested documents.
     *
     * @throws IllegalArgumentException when the question is blank
     */
    public ChatResponse query(ChatRequest request) {
        if (request == null || request.question() == null
                || request.question().isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }

        log.debug("Forwarding question ({} chars) to AI service",
                request.question().length());

        return chatAiClient.query(request);
    }
}

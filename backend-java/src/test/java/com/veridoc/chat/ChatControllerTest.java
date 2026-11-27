package com.veridoc.chat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.veridoc.chat.client.ChatServiceException;
import com.veridoc.chat.controller.ChatController;
import com.veridoc.chat.dto.ChatResponse;
import com.veridoc.chat.dto.Citation;
import com.veridoc.chat.service.ChatService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatService chatService;

    // ---------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------

    @Test
    void blankQuestionReturns400() throws Exception {
        when(chatService.query(any())).thenThrow(
                new IllegalArgumentException("question must not be blank"));

        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("question must not be blank"));
    }

    @Test
    void missingQuestionReturns400() throws Exception {
        // @Valid triggers Jakarta Bean Validation before the service is reached.
        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("question must not be blank"));
    }

    // ---------------------------------------------------------------
    // Successful grounded answer
    // ---------------------------------------------------------------

    @Test
    void validQuestionReturns200WithMappedCitations() throws Exception {
        ChatResponse response = new ChatResponse(
                "You get 25 days of annual leave [1].",
                true,
                "llama3.1:8b",
                List.of(
                        new Citation(
                                "11111111-1111-1111-1111-111111111111",
                                "handbook.pdf",
                                3,
                                7,
                                new BigDecimal("0.8123"),
                                "Employees receive 25 days of paid annual leave per year."
                        )
                )
        );

        when(chatService.query(any())).thenReturn(response);

        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "How many annual leave days do I get?",
                                  "topK": 5,
                                  "minScore": 0.35
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("You get 25 days of annual leave [1]."))
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.modelUsed").value("llama3.1:8b"))
                .andExpect(jsonPath("$.citations[0].documentId")
                        .value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.citations[0].fileName").value("handbook.pdf"))
                .andExpect(jsonPath("$.citations[0].pageNumber").value(3))
                .andExpect(jsonPath("$.citations[0].chunkIndex").value(7))
                .andExpect(jsonPath("$.citations[0].score").value(0.8123))
                .andExpect(jsonPath("$.citations[0].snippet").exists());
    }

    @Test
    void refusedAnswerReturns200WithEmptyCitations() throws Exception {
        ChatResponse response = new ChatResponse(
                "I could not find relevant information in the uploaded documents "
                        + "to answer this question.",
                false,
                "llama3.1:8b",
                List.of()
        );

        when(chatService.query(any())).thenReturn(response);

        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"What is the capital of France?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.citations").isEmpty());
    }

    // ---------------------------------------------------------------
    // Upstream failure
    // ---------------------------------------------------------------

    @Test
    void aiServiceFailureReturns502() throws Exception {
        when(chatService.query(any())).thenThrow(
                new ChatServiceException("AI service call failed: timeout"));

        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"anything\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("AI_SERVICE_ERROR"));
    }
}

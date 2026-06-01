package com.smartretail.dfs.adapter.inbound.rest;

import com.smartretail.dfs.domain.model.exception.ForecastRunNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;
    private static final UUID KNOWN_RUN_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void handleRunNotFound_returns404WithNotFoundCode() throws Exception {
        mockMvc.perform(get("/test/run-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Forecast run not found: " + KNOWN_RUN_ID))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void handleUnexpected_returns500WithInternalErrorCode() throws Exception {
        mockMvc.perform(get("/test/error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @RestController
    static class TestController {

        @GetMapping("/test/run-not-found")
        public void throwRunNotFound() {
            throw new ForecastRunNotFoundException(KNOWN_RUN_ID);
        }

        @GetMapping("/test/error")
        public void throwGeneric() {
            throw new RuntimeException("unexpected failure");
        }
    }
}

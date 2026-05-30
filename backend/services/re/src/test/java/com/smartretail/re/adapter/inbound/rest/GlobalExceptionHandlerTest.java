package com.smartretail.re.adapter.inbound.rest;

import com.smartretail.re.domain.model.exception.InvalidStatusTransitionException;
import com.smartretail.re.domain.model.exception.OptimisticLockException;
import com.smartretail.re.domain.model.exception.PurchaseOrderNotFoundException;
import com.smartretail.re.domain.model.exception.ReplenishmentRuleNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void testPurchaseOrderNotFoundException_returns404() throws Exception {
        mockMvc.perform(get("/test/po-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(containsString("not found")))
                .andExpect(jsonPath("$.timestamp").value(notNullValue()));
    }

    @Test
    void testReplenishmentRuleNotFoundException_returns404() throws Exception {
        mockMvc.perform(get("/test/rule-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(containsString("No active replenishment rule found")))
                .andExpect(jsonPath("$.timestamp").value(notNullValue()));
    }

    @Test
    void testInvalidStatusTransitionException_returns409() throws Exception {
        mockMvc.perform(get("/test/invalid-transition"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.message").value(containsString("Transition failed")))
                .andExpect(jsonPath("$.details.currentStatus").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.timestamp").value(notNullValue()));
    }

    @Test
    void testOptimisticLockException_returns409() throws Exception {
        mockMvc.perform(get("/test/optimistic-lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONCURRENT_MODIFICATION"))
                .andExpect(jsonPath("$.message").value(containsString("version mismatch")))
                .andExpect(jsonPath("$.timestamp").value(notNullValue()));
    }

    @Test
    void testAccessDeniedException_returns403() throws Exception {
        mockMvc.perform(get("/test/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value(containsString("Access is denied")))
                .andExpect(jsonPath("$.timestamp").value(notNullValue()));
    }

    @Test
    void testGeneralException_returns500() throws Exception {
        mockMvc.perform(get("/test/general-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.timestamp").value(notNullValue()));
    }

    @Test
    void testValidationException_returns400() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("name: must not be blank")))
                .andExpect(jsonPath("$.timestamp").value(notNullValue()));
    }

    // ── Dummy Controller & DTO for Exception Testing ─────────────────────────

    @RestController
    static class TestController {

        @GetMapping("/test/po-not-found")
        public void throwPoNotFound() {
            throw new PurchaseOrderNotFoundException(UUID.fromString("00000000-0000-0000-0000-000000000000"));
        }

        @GetMapping("/test/rule-not-found")
        public void throwRuleNotFound() {
            throw new ReplenishmentRuleNotFoundException("SKU-BEV-001", "DC-LONDON");
        }

        @GetMapping("/test/invalid-transition")
        public void throwInvalidTransition() {
            throw new InvalidStatusTransitionException(com.smartretail.re.domain.model.WorkflowStatus.PENDING_APPROVAL, "Transition failed");
        }

        @GetMapping("/test/optimistic-lock")
        public void throwOptimisticLock() {
            throw new OptimisticLockException("Purchase order version mismatch");
        }

        @GetMapping("/test/access-denied")
        public void throwAccessDenied() {
            throw new AccessDeniedException("Access is denied");
        }

        @GetMapping("/test/general-error")
        public void throwGeneralException() throws Exception {
            throw new Exception("Some database error");
        }

        @PostMapping("/test/validation")
        public void testValidation(@Valid @RequestBody DummyDto body) {
        }
    }

    static class DummyDto {
        @NotBlank
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}

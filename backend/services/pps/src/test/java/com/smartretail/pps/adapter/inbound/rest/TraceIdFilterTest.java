package com.smartretail.pps.adapter.inbound.rest;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TraceIdFilterTest {

    @Mock
    private FilterChain chain;

    private TraceIdFilter filter;

    @BeforeEach
    void setUp() {
        filter = new TraceIdFilter();
        MDC.clear();
    }

    @Test
    void setsTraceIdFromHeader_inResponseHeader() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/promotions");
        req.addHeader("X-Amzn-Trace-Id", "trace-pps-001");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader("X-Trace-Id")).isEqualTo("trace-pps-001");
    }

    @Test
    void generatesTraceId_whenHeaderAbsent() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/promotions");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader("X-Trace-Id")).isNotBlank();
    }

    @Test
    void setsCorrelationIdFromHeader_inResponseHeader() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/promotions");
        req.addHeader("X-Correlation-ID", "corr-pps-456");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader("X-Correlation-ID")).isEqualTo("corr-pps-456");
    }

    @Test
    void generatesCorrelationId_whenHeaderAbsent() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/promotions");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader("X-Correlation-ID")).isNotBlank();
    }

    @Test
    void clearsMdcAfterFilterChain() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/promotions");
        req.addHeader("X-Amzn-Trace-Id", "trace-clear");
        req.addHeader("X-Correlation-ID", "corr-clear");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void chainsToNextFilter() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/promotions");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
    }

    @Test
    void bothHeadersPresent_bothEchoedInResponse() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/promotions");
        req.addHeader("X-Amzn-Trace-Id", "trace-pps-xyz");
        req.addHeader("X-Correlation-ID", "corr-pps-xyz");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader("X-Trace-Id")).isEqualTo("trace-pps-xyz");
        assertThat(resp.getHeader("X-Correlation-ID")).isEqualTo("corr-pps-xyz");
    }

    @Test
    void generatesUuidCorrelationId_whenHeaderBlank() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/promotions");
        req.addHeader("X-Correlation-ID", " ");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader("X-Correlation-ID")).isNotBlank();
        assertThat(resp.getHeader("X-Correlation-ID")).isNotEqualTo(" ");
    }
}

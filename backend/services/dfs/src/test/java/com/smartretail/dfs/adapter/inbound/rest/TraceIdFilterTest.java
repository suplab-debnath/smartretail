package com.smartretail.dfs.adapter.inbound.rest;

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
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/forecasts");
        req.addHeader("X-Amzn-Trace-Id", "trace-dfs-001");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader("X-Trace-Id")).isEqualTo("trace-dfs-001");
    }

    @Test
    void generatesTraceId_whenHeaderAbsent() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/forecasts");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader("X-Trace-Id")).isNotBlank();
    }

    @Test
    void setsCorrelationIdFromHeader_inResponseHeader() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/forecasts");
        req.addHeader("X-Correlation-ID", "corr-dfs-456");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader("X-Correlation-ID")).isEqualTo("corr-dfs-456");
    }

    @Test
    void generatesCorrelationId_whenHeaderAbsent() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/forecasts");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader("X-Correlation-ID")).isNotBlank();
    }

    @Test
    void clearsMdcAfterFilterChain() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/forecasts");
        req.addHeader("X-Amzn-Trace-Id", "trace-clear");
        req.addHeader("X-Correlation-ID", "corr-clear");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void chainsToNextFilter() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/forecasts");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
    }

    @Test
    void bothHeadersPresent_bothEchoedInResponse() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/forecasts");
        req.addHeader("X-Amzn-Trace-Id", "trace-dfs-xyz");
        req.addHeader("X-Correlation-ID", "corr-dfs-xyz");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader("X-Trace-Id")).isEqualTo("trace-dfs-xyz");
        assertThat(resp.getHeader("X-Correlation-ID")).isEqualTo("corr-dfs-xyz");
    }

    @Test
    void generatesUuidCorrelationId_whenHeaderBlank() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/forecasts");
        req.addHeader("X-Correlation-ID", "  ");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader("X-Correlation-ID")).isNotBlank();
        assertThat(resp.getHeader("X-Correlation-ID")).isNotEqualTo("  ");
    }
}

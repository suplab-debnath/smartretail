package com.smartretail.ars.adapter.inbound.rest;

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
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/dashboard/store-manager");
        req.addHeader("X-Amzn-Trace-Id", "trace-ars-001");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader("X-Trace-Id")).isEqualTo("trace-ars-001");
    }

    @Test
    void generatesTraceId_whenHeaderAbsent() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/dashboard/store-manager");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader("X-Trace-Id")).isNotBlank();
    }

    @Test
    void setsCorrelationIdFromHeader_inResponseHeader() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/dashboard/sc-planner");
        req.addHeader("X-Correlation-ID", "corr-ars-456");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader("X-Correlation-ID")).isEqualTo("corr-ars-456");
    }

    @Test
    void generatesCorrelationId_whenHeaderAbsent() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/dashboard/executive");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader("X-Correlation-ID")).isNotBlank();
    }

    @Test
    void clearsMdcAfterFilterChain() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/dashboard/store-manager");
        req.addHeader("X-Amzn-Trace-Id", "trace-clear");
        req.addHeader("X-Correlation-ID", "corr-clear");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void chainsToNextFilter() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/dashboard/store-manager");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
    }

    @Test
    void generatesUuidTraceId_whenHeaderBlank() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/dashboard/store-manager");
        req.addHeader("X-Amzn-Trace-Id", "");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader("X-Trace-Id")).isNotBlank();
    }

    @Test
    void differentRequests_generateDistinctIds() throws Exception {
        MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/v1/dashboard/store-manager");
        MockHttpServletResponse resp1 = new MockHttpServletResponse();
        filter.doFilter(req1, resp1, chain);

        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/v1/dashboard/store-manager");
        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        filter.doFilter(req2, resp2, chain);

        assertThat(resp1.getHeader("X-Trace-Id")).isNotEqualTo(resp2.getHeader("X-Trace-Id"));
        assertThat(resp1.getHeader("X-Correlation-ID")).isNotEqualTo(resp2.getHeader("X-Correlation-ID"));
    }
}

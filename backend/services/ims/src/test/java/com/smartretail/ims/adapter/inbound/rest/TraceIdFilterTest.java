package com.smartretail.ims.adapter.inbound.rest;

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
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/inventory/positions");
        req.addHeader("X-Amzn-Trace-Id", "trace-ims-001");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader("X-Trace-Id")).isEqualTo("trace-ims-001");
    }

    @Test
    void generatesTraceId_whenHeaderAbsent() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/inventory/positions");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader("X-Trace-Id")).isNotBlank();
    }

    @Test
    void generatesUuidTraceId_whenHeaderBlank() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/inventory/positions");
        req.addHeader("X-Amzn-Trace-Id", "   ");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader("X-Trace-Id")).isNotBlank();
        assertThat(resp.getHeader("X-Trace-Id")).isNotEqualTo("   ");
    }

    @Test
    void setsCorrelationIdFromHeader_inResponseHeader() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/inventory/positions");
        req.addHeader("X-Correlation-ID", "corr-ims-456");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader("X-Correlation-ID")).isEqualTo("corr-ims-456");
    }

    @Test
    void generatesCorrelationId_whenHeaderAbsent() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/inventory/positions");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader("X-Correlation-ID")).isNotBlank();
    }

    @Test
    void clearsMdcAfterFilterChain() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/inventory/alerts");
        req.addHeader("X-Amzn-Trace-Id", "trace-clear");
        req.addHeader("X-Correlation-ID", "corr-clear");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void chainsToNextFilter() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/inventory/positions");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
    }

    @Test
    void differentRequests_generateDistinctCorrelationIds() throws Exception {
        MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/v1/inventory/positions");
        MockHttpServletResponse resp1 = new MockHttpServletResponse();
        filter.doFilter(req1, resp1, chain);
        String corr1 = resp1.getHeader("X-Correlation-ID");

        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/v1/inventory/positions");
        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        filter.doFilter(req2, resp2, chain);
        String corr2 = resp2.getHeader("X-Correlation-ID");

        assertThat(corr1).isNotEqualTo(corr2);
    }
}

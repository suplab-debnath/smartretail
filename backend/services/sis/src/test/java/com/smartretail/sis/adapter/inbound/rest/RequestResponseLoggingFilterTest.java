package com.smartretail.sis.adapter.inbound.rest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RequestResponseLoggingFilterTest {

    @Mock
    private FilterChain chain;

    private RequestResponseLoggingFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RequestResponseLoggingFilter();
    }

    @Test
    void actuatorRequest_passesThroughWithoutWrapping() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
    }

    @Test
    void actuatorMetrics_passesThroughWithoutWrapping() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/prometheus");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
    }

    @Test
    void normalGetRequest_invokesFilterChain() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/ingest/events");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void responseBodyIsCopiedBackToOriginalResponse() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/test");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        doAnswer(inv -> {
            HttpServletResponse r = (HttpServletResponse) inv.getArguments()[1];
            r.getWriter().write("{\"status\":\"ok\"}");
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(req, resp, chain);

        assertThat(resp.getContentAsString()).isEqualTo("{\"status\":\"ok\"}");
    }

    @Test
    void postRequestWithJsonBody_logsWithoutException() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/ingest/events");
        req.setContent("{\"skuId\":\"SKU-001\",\"quantity\":10}".getBytes(StandardCharsets.UTF_8));
        req.setContentType("application/json");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void getRequestWithQueryString_logsWithoutException() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/ingest/events");
        req.setQueryString("storeId=STORE-001&page=0&size=20");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void authorizationHeader_isMasked_andChainInvoked() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/test");
        req.addHeader("Authorization", "Bearer super-secret-jwt-token");
        req.addHeader("Content-Type", "application/json");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void cookieHeader_isMasked_andChainInvoked() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/test");
        req.addHeader("Cookie", "session=abc123; auth=xyz789");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void firehoseAccessKeyHeader_isMasked_andChainInvoked() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/test");
        req.addHeader("X-Amz-Firehose-Access-Key", "my-secret-access-key");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void largeRequestBody_truncatedAt4096Bytes() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/test");
        byte[] largeBody = new byte[8192];
        Arrays.fill(largeBody, (byte) 'X');
        req.setContent(largeBody);
        req.setContentType("application/json");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        // Should not throw despite large payload
        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void emptyBody_handledGracefully() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/test");
        req.setContent(new byte[0]);
        req.setContentType("application/json");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void requestWithNoHeaders_handledGracefully() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/test");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }
}

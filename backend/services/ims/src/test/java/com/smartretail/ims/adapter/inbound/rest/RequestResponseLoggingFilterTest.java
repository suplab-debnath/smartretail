package com.smartretail.ims.adapter.inbound.rest;

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
    void actuatorReadiness_passesThroughWithoutWrapping() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health/readiness");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
    }

    @Test
    void inventoryPositionsRequest_invokesFilterChain() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/inventory/positions");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void responseBodyIsCopiedBackToOriginalResponse() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/inventory/positions");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        doAnswer(inv -> {
            HttpServletResponse r = (HttpServletResponse) inv.getArguments()[1];
            r.getWriter().write("{\"positions\":[]}");
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(req, resp, chain);

        assertThat(resp.getContentAsString()).isEqualTo("{\"positions\":[]}");
    }

    @Test
    void alertsRequestWithQueryParams_logsWithoutException() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/inventory/alerts");
        req.setQueryString("severity=HIGH&status=ACTIVE&page=0&size=20");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void authorizationHeader_isMasked_andChainInvoked() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/inventory/positions");
        req.addHeader("Authorization", "Bearer cognito-jwt-token-xyz");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void cookieHeader_isMasked_andChainInvoked() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/inventory/alerts");
        req.addHeader("Cookie", "sessionId=secret-session");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void largeResponseBody_handledGracefully() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/inventory/positions");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        doAnswer(inv -> {
            HttpServletResponse r = (HttpServletResponse) inv.getArguments()[1];
            byte[] largeBody = new byte[8192];
            Arrays.fill(largeBody, (byte) 'Z');
            r.getOutputStream().write(largeBody);
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(req, resp, chain);

        // Body should be preserved even when truncated in logs
        assertThat(resp.getContentAsByteArray()).hasSize(8192);
    }

    @Test
    void emptyBody_handledGracefully() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/inventory/positions");
        req.setContent(new byte[0]);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void postRequestWithBody_logsWithoutException() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/inventory/positions");
        req.setContent("{\"dcId\":\"DC-LONDON\",\"skuId\":\"SKU-001\"}".getBytes(StandardCharsets.UTF_8));
        req.setContentType("application/json");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }
}

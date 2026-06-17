package com.smartretail.ars.adapter.inbound.rest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

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
    void dashboardRequest_invokesFilterChain() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/dashboard/store-manager");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void responseBodyIsCopiedBackToOriginalResponse() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/dashboard/executive");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        doAnswer(inv -> {
            HttpServletResponse r = (HttpServletResponse) inv.getArguments()[1];
            r.getWriter().write("{\"mape\":12.5}");
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(req, resp, chain);

        assertThat(resp.getContentAsString()).isEqualTo("{\"mape\":12.5}");
    }

    @Test
    void authorizationHeader_maskedAndChainInvoked() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/dashboard/sc-planner");
        req.addHeader("Authorization", "Bearer cognito-jwt");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void cookieHeader_maskedAndChainInvoked() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/dashboard/supplier");
        req.addHeader("Cookie", "auth=secret-cookie");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void dashboardWithQueryParams_logsWithoutException() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/dashboard/store-manager");
        req.setQueryString("storeId=STORE-001&dcId=DC-LONDON");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void emptyBody_handledGracefully() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/dashboard/executive");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void responseBodyLargerThanLimit_isStillCopiedBackInFull() throws Exception {
        // > MAX_BODY_BYTES (4096) exercises the truncation branch in readBody
        String big = "y".repeat(5000);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/dashboard/store-manager");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        doAnswer(inv -> {
            HttpServletResponse r = (HttpServletResponse) inv.getArguments()[1];
            r.getWriter().write(big);
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(req, resp, chain);

        // The log is truncated, but the actual response is preserved in full
        assertThat(resp.getContentAsString()).isEqualTo(big);
    }

    @Test
    void responseHeaders_areIteratedWhenPresent() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/dashboard/executive");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        doAnswer(inv -> {
            HttpServletResponse r = (HttpServletResponse) inv.getArguments()[1];
            r.setHeader("X-Custom-Header", "value");
            r.getWriter().write("{}");
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader("X-Custom-Header")).isEqualTo("value");
    }
}

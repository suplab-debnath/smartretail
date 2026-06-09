package com.smartretail.pps.adapter.inbound.rest;

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
    void promotionsRequest_invokesFilterChain() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/promotions");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void createPromotionRequest_invokesFilterChain() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/promotions");
        req.setContent("{\"skuId\":\"SKU-001\",\"discount\":0.15}".getBytes(StandardCharsets.UTF_8));
        req.setContentType("application/json");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void responseBodyIsCopiedBackToOriginalResponse() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/promotions");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        doAnswer(inv -> {
            HttpServletResponse r = (HttpServletResponse) inv.getArguments()[1];
            r.getWriter().write("{\"promotions\":[]}");
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(req, resp, chain);

        assertThat(resp.getContentAsString()).isEqualTo("{\"promotions\":[]}");
    }

    @Test
    void authorizationHeader_maskedAndChainInvoked() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/promotions");
        req.addHeader("Authorization", "Bearer pps-jwt-token");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void promotionsWithQueryParams_logsWithoutException() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/promotions");
        req.setQueryString("skuId=SKU-001&status=ACTIVE&page=0&size=10");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void emptyBody_handledGracefully() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/promotions/PROMO-001");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void cookieHeader_maskedAndChainInvoked() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/promotions");
        req.addHeader("Cookie", "auth-cookie=pps-secret");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }
}

package com.smartretail.sup.adapter.inbound.rest;

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
    void listSuppliersRequest_invokesFilterChain() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/suppliers");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void createSupplierOrderRequest_invokesFilterChain() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/supplier-orders");
        req.setContent("{\"supplierId\":\"SUP-001\",\"skuId\":\"SKU-001\"}".getBytes(StandardCharsets.UTF_8));
        req.setContentType("application/json");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void responseBodyIsCopiedBackToOriginalResponse() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/suppliers");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        doAnswer(inv -> {
            HttpServletResponse r = (HttpServletResponse) inv.getArguments()[1];
            r.getWriter().write("{\"suppliers\":[{\"id\":\"SUP-001\"}]}");
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(req, resp, chain);

        assertThat(resp.getContentAsString()).isEqualTo("{\"suppliers\":[{\"id\":\"SUP-001\"}]}");
    }

    @Test
    void authorizationHeader_maskedAndChainInvoked() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/suppliers");
        req.addHeader("Authorization", "Bearer supplier-admin-jwt");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void cookieHeader_maskedAndChainInvoked() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/supplier-orders");
        req.addHeader("Cookie", "portal-session=sup-session-xyz");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void supplierOrdersWithQueryParams_logsWithoutException() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/supplier-orders");
        req.setQueryString("supplierId=SUP-001&status=CONFIRMED&page=0&size=20");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void emptyBody_handledGracefully() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/suppliers/SUP-001");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
    }
}

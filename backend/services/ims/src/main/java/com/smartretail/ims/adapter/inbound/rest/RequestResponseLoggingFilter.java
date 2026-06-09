package com.smartretail.ims.adapter.inbound.rest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
@Order(2)
public class RequestResponseLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestResponseLoggingFilter.class);
    private static final int MAX_BODY_BYTES = 4096;
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "cookie", "x-api-key", "x-amz-firehose-access-key"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/actuator")) {
            filterChain.doFilter(request, response);
            return;
        }

        var cachingRequest = new ContentCachingRequestWrapper(request, MAX_BODY_BYTES);
        var cachingResponse = new ContentCachingResponseWrapper(response);
        long start = System.currentTimeMillis();

        try {
            filterChain.doFilter(cachingRequest, cachingResponse);
        } finally {
            long durationMs = System.currentTimeMillis() - start;
            logRequest(cachingRequest);
            logResponse(cachingResponse, durationMs);
            cachingResponse.copyBodyToResponse();
        }
    }

    private void logRequest(ContentCachingRequestWrapper request) {
        log.info("Incoming HTTP request",
                StructuredArguments.kv("http.method", request.getMethod()),
                StructuredArguments.kv("http.uri", request.getRequestURI()),
                StructuredArguments.kv("http.queryString", request.getQueryString()),
                StructuredArguments.kv("http.contentType", request.getContentType()),
                StructuredArguments.kv("http.requestHeaders", maskedHeaders(request)),
                StructuredArguments.kv("http.requestBody", readBody(request.getContentAsByteArray()))
        );
    }

    private void logResponse(ContentCachingResponseWrapper response, long durationMs) {
        log.info("Outgoing HTTP response",
                StructuredArguments.kv("http.status", response.getStatus()),
                StructuredArguments.kv("http.durationMs", durationMs),
                StructuredArguments.kv("http.responseHeaders", responseHeaders(response)),
                StructuredArguments.kv("http.responseBody", readBody(response.getContentAsByteArray()))
        );
    }

    private Map<String, String> maskedHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) return headers;
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, SENSITIVE_HEADERS.contains(name.toLowerCase()) ? "***" : request.getHeader(name));
        }
        return headers;
    }

    private Map<String, String> responseHeaders(ContentCachingResponseWrapper response) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : response.getHeaderNames()) {
            headers.put(name, response.getHeader(name));
        }
        return headers;
    }

    private String readBody(byte[] body) {
        if (body == null || body.length == 0) return "";
        int limit = Math.min(body.length, MAX_BODY_BYTES);
        String content = new String(body, 0, limit, StandardCharsets.UTF_8);
        return body.length > MAX_BODY_BYTES ? content + "...[truncated]" : content;
    }
}

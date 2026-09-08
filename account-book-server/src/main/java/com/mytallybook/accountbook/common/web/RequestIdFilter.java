package com.mytallybook.accountbook.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    public static final String REQUEST_ID_MDC_KEY = "requestId";

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put(REQUEST_ID_MDC_KEY, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(REQUEST_ID_MDC_KEY);
        }
    }

    public static String getRequestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        return requestId instanceof String value ? value : null;
    }

    private String resolveRequestId(String candidate) {
        if (candidate != null && SAFE_REQUEST_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }
}

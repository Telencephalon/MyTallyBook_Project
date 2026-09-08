package com.mytallybook.accountbook.database;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockServletContext;

import static org.assertj.core.api.Assertions.assertThat;

/** Offline request-adapter regression; never opens a datasource or a Spring application context. */
class BookkeepingMysqlRequestTests {
    @Test
    void encodedLiteralKeywordIsDecodedExactlyOnce() {
        var request = BookkeepingMysqlIntegrationTests.gateRequest("GET",
                "/entries?keyword=%25_%5C&dateFrom=9999-12-31&dateTo=9999-12-31")
                .buildRequest(new MockServletContext());

        assertThat(request.getRequestURI()).isEqualTo("/api/v1/entries");
        assertThat(request.getParameter("keyword")).isEqualTo("%_\\");
        assertThat(request.getParameter("dateFrom")).isEqualTo("9999-12-31");
        assertThat(request.getParameter("dateTo")).isEqualTo("9999-12-31");
    }

    @Test
    void encodedQueryDelimitersAndPercentTextRemainOneLiteralValue() {
        var request = BookkeepingMysqlIntegrationTests.gateRequest("GET",
                "/entries?keyword=%E9%A4%90%E9%A5%AE%26%3D%2B%2525")
                .buildRequest(new MockServletContext());

        assertThat(request.getParameterMap()).containsOnlyKeys("keyword");
        assertThat(request.getParameter("keyword")).isEqualTo("餐饮&=+%25");
    }

    @Test
    void mutationPathAndVersionRemainIntact() {
        var request = BookkeepingMysqlIntegrationTests.gateRequest("DELETE", "/entries/42?version=3")
                .buildRequest(new MockServletContext());

        assertThat(request.getMethod()).isEqualTo("DELETE");
        assertThat(request.getRequestURI()).isEqualTo("/api/v1/entries/42");
        assertThat(request.getParameter("version")).isEqualTo("3");
    }
}

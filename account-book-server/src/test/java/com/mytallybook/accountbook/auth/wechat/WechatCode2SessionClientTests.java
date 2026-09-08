package com.mytallybook.accountbook.auth.wechat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mytallybook.accountbook.auth.config.WechatProperties;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WechatCode2SessionClientTests {

    private MockRestServiceServer server;
    private WechatCode2SessionClient client;
    private Logger clientLogger;
    private ListAppender<ILoggingEvent> logAppender;
    private boolean loggerWasAdditive;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new WechatCode2SessionClient(builder, properties());
        clientLogger = (Logger) LoggerFactory.getLogger(WechatCode2SessionClient.class);
        loggerWasAdditive = clientLogger.isAdditive();
        clientLogger.setAdditive(false);
        logAppender = new ListAppender<>();
        logAppender.start();
        clientLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        clientLogger.detachAppender(logAppender);
        clientLogger.setAdditive(loggerWasAdditive);
        logAppender.stop();
    }

    @Test
    void exchangesTheCompleteOfficialResponseButReturnsOnlyWechatIdentity() {
        server.expect(once(), method(HttpMethod.GET))
                .andExpect(request -> {
                    var query = UriComponentsBuilder.fromUri(request.getURI())
                            .build()
                            .getQueryParams();
                    assertThat(query.getFirst("appid")).isEqualTo("test-app-id");
                    assertThat(query.getFirst("secret")).isEqualTo("test-app-secret");
                    assertThat(query.getFirst("js_code")).isEqualTo("fresh-code_123");
                    assertThat(query.getFirst("grant_type")).isEqualTo("authorization_code");
                })
                .andRespond(withSuccess("""
                        {
                          "openid": "openid-1",
                          "session_key": "must-not-escape",
                          "unionid": "union-1",
                          "errcode": 0,
                          "errmsg": "ok"
                        }
                        """, MediaType.parseMediaType("application/json; encoding=utf-8")));

        WechatIdentity identity = client.exchange("fresh-code_123");

        assertThat(identity).isEqualTo(new WechatIdentity("openid-1", "union-1"));
        assertThat(WechatIdentity.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("openid", "unionid");
        assertThat(logAppender.list).isEmpty();
        server.verify();
    }

    @Test
    void readsValidJsonWhenWechatLabelsItTextPlain() {
        assertCompatibleJsonResponse(MediaType.TEXT_PLAIN);
    }

    @Test
    void readsValidJsonWhenWechatOmitsContentType() {
        assertCompatibleJsonResponse(null);
    }

    @Test
    void readsValidJsonWhenWechatLabelsItOctetStream() {
        assertCompatibleJsonResponse(MediaType.APPLICATION_OCTET_STREAM);
    }

    @ParameterizedTest
    @MethodSource("invalidCompatibilityResponses")
    void rejectsNonObjectOrNonJsonBodiesRegardlessOfContentType(
            String responseBody,
            MediaType contentType,
            String expectedContentTypeCategory,
            String forbiddenBodyMarker,
            String forbiddenMimeMarker
    ) {
        assertServiceError(response(responseBody, contentType));

        assertSingleSafeWarning(
                List.of(
                        "failureType=rest_client",
                        "contentTypeCategory=" + expectedContentTypeCategory,
                        "exceptionType="
                ),
                forbiddenBodyMarker,
                forbiddenMimeMarker
        );
    }

    @ParameterizedTest
    @CsvSource({
            "40029, WECHAT_CODE_INVALID",
            "40226, WECHAT_LOGIN_BLOCKED",
            "-1, WECHAT_SERVICE_UNAVAILABLE",
            "45011, WECHAT_SERVICE_UNAVAILABLE",
            "41002, WECHAT_SERVICE_ERROR",
            "99999, WECHAT_SERVICE_ERROR"
    })
    void mapsWechatErrorCodesWithoutLeakingWechatMessages(
            int wechatErrorCode,
            ErrorCode expectedErrorCode
    ) {
        server.expect(once(), method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"errcode\":" + wechatErrorCode
                                + ",\"errmsg\":\"secret diagnostic text\"}",
                        MediaType.parseMediaType("application/json; encoding=utf-8")
                ));

        assertThatThrownBy(() -> client.exchange("single-use-code"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(expectedErrorCode);
                    assertThat(exception.getMessage()).doesNotContain("secret diagnostic text");
                });
        assertSingleSafeWarning(
                List.of("failureType=wechat_error", "wechatErrcode=" + wechatErrorCode),
                "secret diagnostic text"
        );
        server.verify();
    }

    @Test
    void logsMissingIdentityAsAClassificationWithoutResponseData() {
        assertServiceError(withSuccess(
                "{\"session_key\":\"hidden-session-key\",\"unionid\":\"hidden-unionid\"}",
                MediaType.APPLICATION_JSON
        ));

        assertSingleSafeWarning(
                List.of("failureType=missing_identity"),
                "hidden-session-key",
                "hidden-unionid"
        );
    }

    @Test
    void logsMalformedJsonAsRestClientFailureWithoutPayload() {
        assertServiceError(withSuccess(
                "not-json-secret-payload",
                MediaType.APPLICATION_JSON
        ));

        assertSingleSafeWarning(
                List.of("failureType=rest_client", "exceptionType="),
                "not-json-secret-payload"
        );
    }

    @Test
    void logsEmptyResponseAsAClassification() {
        assertServiceError(withSuccess("", MediaType.APPLICATION_JSON));

        assertSingleSafeWarning(List.of("failureType=null_response"));
    }

    @Test
    void logsNonSuccessHttpAsNumericStatusWithoutResponseBody() {
        assertServiceError(withStatus(HttpStatus.BAD_GATEWAY)
                .header("X-Sensitive-Upstream", "secret-upstream-header")
                .body("secret-upstream-response-body"));

        assertSingleSafeWarning(
                List.of("failureType=http_status", "upstreamHttpStatus=502"),
                "secret-upstream-response-body",
                "secret-upstream-header"
        );
    }

    @Test
    void logsNonTimeoutTransportFailureByTypesWithoutExceptionMessage() {
        server.expect(once(), method(HttpMethod.GET))
                .andRespond(request -> {
                    throw new IOException("transport failed for secret query data");
                });

        assertThatThrownBy(() -> client.exchange("single-use-code"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.WECHAT_SERVICE_ERROR));
        assertSingleSafeWarning(
                List.of(
                        "failureType=resource_access",
                        "exceptionType=",
                        "causeType=java.io.IOException"
                ),
                "transport failed for secret query data"
        );
        server.verify();
    }

    @Test
    void mapsSocketTimeoutToGatewayTimeout() {
        server.expect(once(), method(HttpMethod.GET))
                .andRespond(request -> {
                    throw new SocketTimeoutException("timed out while reading secret response");
                });

        assertThatThrownBy(() -> client.exchange("single-use-code"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.WECHAT_SERVICE_TIMEOUT);
                    assertThat(exception.getMessage()).doesNotContain("secret response");
                });
        assertSingleSafeWarning(
                List.of(
                        "failureType=timeout",
                        "exceptionType=",
                        "causeType=java.net.SocketTimeoutException"
                ),
                "timed out while reading secret response"
        );
        server.verify();
    }

    private void assertServiceError(
            ResponseCreator responseCreator
    ) {
        server.expect(once(), method(HttpMethod.GET)).andRespond(responseCreator);
        assertThatThrownBy(() -> client.exchange("single-use-code"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.WECHAT_SERVICE_ERROR));
        server.verify();
    }

    private void assertCompatibleJsonResponse(MediaType contentType) {
        server.expect(once(), method(HttpMethod.GET))
                .andRespond(response("""
                        {
                          "openid": "compatible-openid",
                          "session_key": "must-not-escape",
                          "unionid": "compatible-unionid",
                          "errcode": 0,
                          "errmsg": "ok"
                        }
                        """, contentType));

        assertThat(client.exchange("single-use-code"))
                .isEqualTo(new WechatIdentity("compatible-openid", "compatible-unionid"));
        assertThat(logAppender.list).isEmpty();
        server.verify();
    }

    private static DefaultResponseCreator response(String body, MediaType contentType) {
        DefaultResponseCreator response = withStatus(HttpStatus.OK).body(body);
        return contentType == null ? response : response.contentType(contentType);
    }

    private static Stream<Arguments> invalidCompatibilityResponses() {
        return Stream.of(
                Arguments.of(
                        "<html>secret-html-marker</html>",
                        MediaType.TEXT_HTML,
                        "html",
                        "secret-html-marker",
                        "secret-html-marker"
                ),
                Arguments.of(
                        "not-json-secret-text-marker",
                        MediaType.TEXT_PLAIN,
                        "text_plain",
                        "secret-text-marker",
                        "secret-text-marker"
                ),
                Arguments.of(
                        "{\"openid\":\"first-secret-root\"} {\"openid\":\"second-secret-root\"}",
                        MediaType.APPLICATION_OCTET_STREAM,
                        "octet_stream",
                        "secret-root",
                        "secret-root"
                ),
                Arguments.of(
                        "\"secret-scalar-marker\"",
                        MediaType.parseMediaType(
                                "application/x-wechat-response; debug=secret-mime-marker"
                        ),
                        "other",
                        "secret-scalar-marker",
                        "secret-mime-marker"
                ),
                Arguments.of(
                        "[{\"openid\":\"secret-array-marker\"}]",
                        null,
                        "missing",
                        "secret-array-marker",
                        "secret-array-marker"
                )
        );
    }

    private void assertSingleSafeWarning(
            List<String> expectedValues,
            String... forbiddenValues
    ) {
        assertThat(logAppender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            for (String expected : expectedValues) {
                assertThat(event.getFormattedMessage()).contains(expected);
            }
            for (String forbidden : forbiddenValues) {
                assertThat(event.getFormattedMessage()).doesNotContain(forbidden);
                assertThat(Arrays.toString(event.getArgumentArray())).doesNotContain(forbidden);
            }
            for (String credentialOrCode : List.of(
                    "test-app-id",
                    "test-app-secret",
                    "single-use-code"
            )) {
                assertThat(event.getFormattedMessage()).doesNotContain(credentialOrCode);
                assertThat(Arrays.toString(event.getArgumentArray())).doesNotContain(credentialOrCode);
            }
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private static WechatProperties properties() {
        return new WechatProperties(
                "test-app-id",
                "test-app-secret",
                URI.create("https://api.weixin.qq.com/sns/jscode2session"),
                Duration.ofSeconds(3),
                Duration.ofSeconds(5)
        );
    }
}

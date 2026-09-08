package com.mytallybook.accountbook.common;

import com.mytallybook.accountbook.AccountBookServerApplication;
import com.mytallybook.accountbook.common.api.ApiResponse;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import com.mytallybook.accountbook.common.web.RequestIdFilter;
import com.mytallybook.accountbook.security.CurrentUser;
import com.mytallybook.accountbook.security.MemberRole;
import com.mytallybook.accountbook.security.SessionTokenVerifier;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AccountBookServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@Import({
        ApiInfrastructureIntegrationTests.TestEndpoints.class,
        ApiInfrastructureIntegrationTests.SecurityTestConfiguration.class
})
class ApiInfrastructureIntegrationTests {

    private static final String VALID_TOKEN = "valid-member-token";

    private final MockMvc mockMvc;
    private final ApplicationContext applicationContext;

    @Autowired
    ApiInfrastructureIntegrationTests(MockMvc mockMvc, ApplicationContext applicationContext) {
        this.mockMvc = mockMvc;
        this.applicationContext = applicationContext;
    }

    @Test
    void publicAuthenticationEndpointDoesNotRequireABearerToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/invites/accept"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void protectedEndpointReturnsStructuredUnauthorizedResponse() throws Exception {
        mockMvc.perform(get("/api/v1/test/success"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("请先登录"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void invalidBearerTokenReturnsStructuredUnauthorizedResponse() throws Exception {
        mockMvc.perform(get("/api/v1/test/success")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void tokenVerificationFailureReturnsStructuredInternalErrorWithoutLeakingDetails() throws Exception {
        mockMvc.perform(get("/api/v1/test/success")
                        .header("Authorization", "Bearer exploding-token"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("服务暂时不可用"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("database-secret"));
    }

    @Test
    void tokenVerificationFailureDoesNotLogAnAttackerControlledRequestPath(
            CapturedOutput output
    ) throws Exception {
        mockMvc.perform(get("/api/v1/test/path-audit-marker")
                        .header("Authorization", "Bearer exploding-token")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, "token-failure-request-789"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(output.getAll())
                .contains("requestId=token-failure-request-789")
                .contains("method=GET")
                .contains("route=<pre-routing>")
                .contains("exceptionType=java.lang.IllegalStateException")
                .containsPattern(
                        "location=com\\.mytallybook\\.accountbook\\..+#.+\\(.+\\.java:\\d+\\)"
                )
                .doesNotContain("path-audit-marker")
                .doesNotContain("exploding-token")
                .doesNotContain("database-secret")
                .doesNotContain("Authorization");
    }

    @Test
    void validRequestIdIsEchoedInHeaderAndBody() throws Exception {
        mockMvc.perform(get("/api/v1/test/success")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .header(RequestIdFilter.REQUEST_ID_HEADER, "client-request-123"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.REQUEST_ID_HEADER, "client-request-123"))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.value").value("pong"))
                .andExpect(jsonPath("$.requestId").value("client-request-123"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void invalidRequestIdIsReplacedWithASafeGeneratedValue() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/test/success")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .header(RequestIdFilter.REQUEST_ID_HEADER, "invalid request id"))
                .andExpect(status().isOk())
                .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER))
                .andReturn();

        String responseRequestId = result.getResponse()
                .getHeader(RequestIdFilter.REQUEST_ID_HEADER);
        assertThat(responseRequestId)
                .isNotBlank()
                .doesNotContain(" ")
                .isNotEqualTo("invalid request id");
        assertThat(result.getResponse().getContentAsString())
                .contains("\"requestId\":\"" + responseRequestId + "\"");
    }

    @Test
    void beanValidationFailureUsesStableErrorCodeAndFieldDetails() throws Exception {
        mockMvc.perform(post("/api/v1/test/validated")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.name").value("名称不能为空"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void requestParameterTypeMismatchUsesStructuredBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/test/paged")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .param("page", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("请求参数不正确"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void missingRequiredRequestParameterUsesStructuredBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/test/paged")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("请求参数不正确"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void requestParameterMethodValidationUsesStructuredBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/test/paged")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("请求参数不正确"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void unsupportedHttpMethodUsesStructuredMethodNotAllowedResponse() throws Exception {
        mockMvc.perform(post("/api/v1/test/success")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("请求方法不受支持"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void unsupportedMediaTypeUsesStructuredUnsupportedMediaTypeResponse() throws Exception {
        mockMvc.perform(post("/api/v1/test/validated")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("request-body-secret"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.message").value("请求内容类型不受支持"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void dataIntegrityFailureUsesStructuredConflictWithoutLeakingDetails() throws Exception {
        mockMvc.perform(get("/api/v1/test/data-integrity")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("请求冲突"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("sensitive-database-detail"));
    }

    @Test
    void optimisticLockFailureUsesStructuredConflictWithoutLeakingDetails() throws Exception {
        mockMvc.perform(get("/api/v1/test/optimistic-lock")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("请求冲突"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("sensitive-optimistic-detail"));
    }

    @Test
    void businessConflictUsesHttp409AndStableErrorCode() throws Exception {
        mockMvc.perform(get("/api/v1/test/conflict")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("测试冲突"));
    }

    @Test
    void unexpectedFailureDoesNotLeakItsInternalMessage() throws Exception {
        mockMvc.perform(get("/api/v1/test/failure")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("服务暂时不可用"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("sensitive-internal-message"));
    }

    @Test
    void unexpectedFailureLogsSafeRequestLocationWithoutRequestSecrets(
            CapturedOutput output
    ) throws Exception {
        mockMvc.perform(post("/api/v1/test/failure")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .header(RequestIdFilter.REQUEST_ID_HEADER, "failure-request-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"request-body-secret\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(output.getAll())
                .contains("requestId=failure-request-123")
                .contains("method=POST")
                .contains("path=/api/v1/test/failure")
                .contains("exceptionType=java.lang.IllegalStateException")
                .containsPattern(
                        "location=com\\.mytallybook\\.accountbook\\..+#.+\\(.+\\.java:\\d+\\)"
                )
                .doesNotContain(VALID_TOKEN)
                .doesNotContain("request-body-secret")
                .doesNotContain("sensitive-internal-message")
                .doesNotContain("Authorization");
    }

    @Test
    void memberCannotAccessOwnerOnlyEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/test/owner-only")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void unknownApiPathReturnsStructuredNotFoundResponse() throws Exception {
        mockMvc.perform(get("/api/v1/not-present")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("资源不存在"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void actuatorHealthRemainsAnonymous() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void applicationDoesNotCreateGeneratedPasswordUserDetailsService() {
        assertThat(applicationContext.getBeansOfType(UserDetailsService.class)).isEmpty();
    }
    @Test
    void lockFailuresAreSafeConflictsButArbitrarySqlFailureIsNot() throws Exception {
        for (String path : java.util.List.of("lock-timeout", "deadlock")) {
            mockMvc.perform(get("/api/v1/test/" + path).header("Authorization", "Bearer " + VALID_TOKEN))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"))
                    .andExpect(jsonPath("$.message").value("请求冲突"))
                    .andExpect(jsonPath("$.details").doesNotExist());
        }
        mockMvc.perform(get("/api/v1/test/arbitrary-sql").header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @RestController
    static class TestEndpoints {
        @GetMapping("/api/v1/test/lock-timeout")
        void lockTimeout() { throw new org.springframework.dao.CannotAcquireLockException("dummy-sensitive-lock"); }
        @GetMapping("/api/v1/test/deadlock")
        void deadlock() { throw new org.springframework.dao.PessimisticLockingFailureException("dummy-sensitive-deadlock"); }
        @GetMapping("/api/v1/test/arbitrary-sql")
        void arbitrarySql() { throw new org.springframework.jdbc.BadSqlGrammarException("dummy-sensitive-task", "dummy-sensitive-sql", new java.sql.SQLException("dummy-sensitive-error")); }

        @GetMapping("/api/v1/test/success")
        ApiResponse<Map<String, String>> success(HttpServletRequest request) {
            return ApiResponse.success(
                    Map.of("value", "pong"),
                    RequestIdFilter.getRequestId(request)
            );
        }

        @PostMapping("/api/v1/test/validated")
        ApiResponse<Map<String, String>> validated(
                @Valid @RequestBody ValidatedRequest body,
                HttpServletRequest request
        ) {
            return ApiResponse.success(
                    Map.of("name", body.name()),
                    RequestIdFilter.getRequestId(request)
            );
        }

        @GetMapping("/api/v1/test/paged")
        ApiResponse<Map<String, Integer>> paged(
                @RequestParam @Min(value = 1, message = "页码必须大于等于1") int page,
                HttpServletRequest request
        ) {
            return ApiResponse.success(
                    Map.of("page", page),
                    RequestIdFilter.getRequestId(request)
            );
        }

        @GetMapping("/api/v1/test/conflict")
        void conflict() {
            throw new BusinessException(ErrorCode.CONFLICT, "测试冲突");
        }

        @GetMapping("/api/v1/test/failure")
        void failure() {
            throw new IllegalStateException("sensitive-internal-message");
        }

        @PostMapping(
                path = "/api/v1/test/failure",
                consumes = MediaType.APPLICATION_JSON_VALUE
        )
        void failureWithBody(@RequestBody String ignored) {
            throw new IllegalStateException("sensitive-internal-message");
        }

        @GetMapping("/api/v1/test/data-integrity")
        void dataIntegrityFailure() {
            throw new DataIntegrityViolationException("sensitive-database-detail");
        }

        @GetMapping("/api/v1/test/optimistic-lock")
        void optimisticLockFailure() {
            throw new OptimisticLockingFailureException("sensitive-optimistic-detail");
        }

        @PreAuthorize("hasRole('OWNER')")
        @GetMapping("/api/v1/test/owner-only")
        void ownerOnly() {
        }
    }

    record ValidatedRequest(@NotBlank(message = "名称不能为空") String name) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityTestConfiguration {

        @Bean
        @Primary
        SessionTokenVerifier testSessionTokenVerifier() {
            return token -> {
                if ("exploding-token".equals(token)) {
                    throw new IllegalStateException("database-secret");
                }
                return VALID_TOKEN.equals(token)
                        ? Optional.of(new CurrentUser(101L, 1L, 1001L, MemberRole.MEMBER))
                        : Optional.empty();
            };
        }
    }
}

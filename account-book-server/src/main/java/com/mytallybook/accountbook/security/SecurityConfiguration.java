package com.mytallybook.accountbook.security;

import com.mytallybook.accountbook.common.web.RequestIdFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

    @Bean
    @ConditionalOnMissingBean(SessionTokenVerifier.class)
    SessionTokenVerifier rejectingSessionTokenVerifier() {
        return ignored -> Optional.empty();
    }

    @Bean
    RequestIdFilter requestIdFilter() {
        return new RequestIdFilter();
    }

    @Bean
    RestAuthenticationEntryPoint restAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new RestAuthenticationEntryPoint(objectMapper);
    }

    @Bean
    RestAccessDeniedHandler restAccessDeniedHandler(ObjectMapper objectMapper) {
        return new RestAccessDeniedHandler(objectMapper);
    }

    @Bean
    BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter(
            SessionTokenVerifier sessionTokenVerifier,
            RestAuthenticationEntryPoint authenticationEntryPoint
    ) {
        return new BearerTokenAuthenticationFilter(sessionTokenVerifier, authenticationEntryPoint);
    }

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration(
            RequestIdFilter requestIdFilter
    ) {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(requestIdFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<BearerTokenAuthenticationFilter> bearerTokenFilterRegistration(
            BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter
    ) {
        FilterRegistrationBean<BearerTokenAuthenticationFilter> registration =
                new FilterRegistrationBean<>(bearerTokenAuthenticationFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RequestIdFilter requestIdFilter,
            BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler
    ) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/auth/wechat/login",
                                "/api/v1/auth/bootstrap",
                                "/api/v1/auth/invites/accept"
                        ).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(requestIdFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(bearerTokenAuthenticationFilter, RequestIdFilter.class);

        return http.build();
    }
}

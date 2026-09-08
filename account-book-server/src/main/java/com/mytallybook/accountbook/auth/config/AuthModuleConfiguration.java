package com.mytallybook.accountbook.auth.config;

import com.mytallybook.accountbook.auth.session.SessionTokenService;
import com.mytallybook.accountbook.auth.wechat.WechatCode2SessionClient;
import com.mytallybook.accountbook.auth.wechat.WechatSessionClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.security.SecureRandom;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AuthProperties.class, WechatProperties.class})
public class AuthModuleConfiguration {

    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }

    @Bean
    SecureRandom sessionSecureRandom() {
        return new SecureRandom();
    }

    @Bean
    SessionTokenService sessionTokenService(
            AuthProperties properties,
            SecureRandom secureRandom,
            Clock clock
    ) {
        return new SessionTokenService(properties, secureRandom, clock);
    }

    @Bean
    WechatSessionClient wechatSessionClient(WechatProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return new WechatCode2SessionClient(
                RestClient.builder().requestFactory(requestFactory),
                properties
        );
    }
}

package com.mytallybook.accountbook;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "DB_URL=jdbc:mysql://database.invalid:3306/account_book_test",
                "DB_USERNAME=test-application-user",
                "DB_PASSWORD=test-password"
        }
)
@ActiveProfiles("test")
class RuntimeConfigurationTests {

    @Autowired
    private Environment environment;

    @Test
    void localRuntimeDefaultsToTheLoopbackInterfaceOnPort7631() {
        assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
        assertThat(environment.getProperty("server.port")).isEqualTo("7631");
    }

    @Test
    void dataSourceUsesTheInjectedConnectionSettings() {
        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:mysql://database.invalid:3306/account_book_test");
        assertThat(environment.getProperty("spring.datasource.username"))
                .isEqualTo("test-application-user");
        assertThat(environment.getProperty("spring.datasource.password"))
                .isEqualTo("test-password");
    }

    @Test
    void persistenceDefaultsUseTheBoundedPoolAndValidatedFlywaySchema() {
        assertThat(environment.getProperty("spring.datasource.hikari.maximum-pool-size"))
                .isEqualTo("5");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
                .isEqualTo("validate");
        assertThat(environment.getProperty("spring.flyway.enabled"))
                .isEqualTo("true");
    }

    @Test
    void consoleLogPatternIncludesTheRequestIdFromMdc() {
        assertThat(environment.getProperty("logging.pattern.console"))
                .contains("requestId=%X{requestId:-}");
    }
}

package com.mytallybook.accountbook;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "SERVER_PORT=17631",
                "DB_URL=jdbc:mysql://database.invalid:3306/account_book",
                "DB_USERNAME=production-application-user",
                "DB_PASSWORD=production-password"
        }
)
@ActiveProfiles({"prod", "test"})
class ProductionConfigurationTests {

    @Autowired
    private Environment environment;

    @Test
    void productionHttpEndpointRemainsLoopbackOnlyAndAllowsAnExplicitPortOverride() {
        assertEquals("127.0.0.1", environment.getProperty("server.address"));
        assertEquals("17631", environment.getProperty("server.port"));
    }

    @Test
    void productionDatabaseUsesTheInjectedConnectionSettings() {
        assertEquals(
                "jdbc:mysql://database.invalid:3306/account_book",
                environment.getProperty("spring.datasource.url")
        );
        assertEquals(
                "production-application-user",
                environment.getProperty("spring.datasource.username")
        );
        assertEquals(
                "production-password",
                environment.getProperty("spring.datasource.password")
        );
    }
}

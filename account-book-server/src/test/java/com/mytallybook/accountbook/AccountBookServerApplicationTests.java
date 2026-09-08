package com.mytallybook.accountbook;

import com.mytallybook.accountbook.security.DatabaseSessionTokenVerifier;
import com.mytallybook.accountbook.security.SessionTokenVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AccountBookServerApplicationTests {

    @Autowired
    private SessionTokenVerifier sessionTokenVerifier;

    @Test
    void contextLoads() {
        assertThat(sessionTokenVerifier).isInstanceOf(DatabaseSessionTokenVerifier.class);
    }

}

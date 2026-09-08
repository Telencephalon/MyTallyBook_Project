package com.mytallybook.accountbook.common.error;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SafeExceptionLocationTests {

    @Test
    void returnsTheFirstApplicationFrameInStackOrder() {
        RuntimeException exception = new RuntimeException();
        exception.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("org.springframework.web.Dispatcher", "dispatch", "Dispatcher.java", 10),
                new StackTraceElement(
                        "com.mytallybook.accountbook.ledger.LedgerService",
                        "createEntry",
                        "LedgerService.java",
                        42
                ),
                new StackTraceElement(
                        "com.mytallybook.accountbook.auth.service.AuthService",
                        "login",
                        "AuthService.java",
                        84
                )
        });

        assertThat(SafeExceptionLocation.firstApplicationFrame(exception))
                .isEqualTo(
                        "com.mytallybook.accountbook.ledger.LedgerService"
                                + "#createEntry(LedgerService.java:42)"
                );
    }
}

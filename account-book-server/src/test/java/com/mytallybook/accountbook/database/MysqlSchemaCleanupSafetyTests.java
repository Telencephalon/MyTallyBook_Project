package com.mytallybook.accountbook.database;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class MysqlSchemaCleanupSafetyTests {
    // Catches a forgotten fail-closed transition: later suites could clean under a surviving worker.
    @Test void unconfirmedShutdownPermanentlyForbidsLaterCleanup() {
        MysqlSchemaCleanupSafety safety = new MysqlSchemaCleanupSafety();
        assertThatCode(safety::requireAllowed).doesNotThrowAnyException();
        safety.forbidCleanup();
        assertThatThrownBy(safety::requireAllowed).isInstanceOf(IllegalStateException.class);
        safety.forbidCleanup();
        assertThatThrownBy(safety::requireAllowed).isInstanceOf(IllegalStateException.class);
    }

    // Catches unsafe state publication between the worker supervisor and the next suite thread.
    @Test void cleanupDenialIsVisibleAcrossThreads() throws Exception {
        MysqlSchemaCleanupSafety safety = new MysqlSchemaCleanupSafety();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(safety::forbidCleanup).get(2, TimeUnit.SECONDS);
            assertThatThrownBy(safety::requireAllowed).isInstanceOf(IllegalStateException.class);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }
}

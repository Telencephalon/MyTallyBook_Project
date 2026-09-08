package com.mytallybook.accountbook.database;

/** Process-local fail-closed cleanup fuse, not a lock or cross-process coordination. */
final class MysqlSchemaCleanupSafety {
    private volatile boolean forbidden;
    void forbidCleanup() { forbidden = true; }
    void requireAllowed() {
        if (forbidden) throw new IllegalStateException("Test workers were not confirmed stopped; schema cleanup is forbidden for this process");
    }
}

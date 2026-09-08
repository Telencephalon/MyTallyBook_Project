package com.mytallybook.accountbook.common.error;

public final class SafeExceptionLocation {

    private static final String APPLICATION_PACKAGE = "com.mytallybook.accountbook.";
    private static final String UNAVAILABLE = "<unavailable>";

    private SafeExceptionLocation() {
    }

    public static String firstApplicationFrame(Throwable exception) {
        for (StackTraceElement frame : exception.getStackTrace()) {
            if (frame.getClassName().startsWith(APPLICATION_PACKAGE)) {
                return "%s#%s(%s:%d)".formatted(
                        frame.getClassName(),
                        frame.getMethodName(),
                        frame.getFileName() == null ? UNAVAILABLE : frame.getFileName(),
                        frame.getLineNumber()
                );
            }
        }
        return UNAVAILABLE;
    }
}

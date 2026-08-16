package dev.gamersden.common.trace;

import org.slf4j.MDC;

import java.util.UUID;

/** MDC keys shared by the trace filter, the JSON log layout and the error envelope. */
public final class TraceId {

    public static final String MDC_KEY = "traceId";
    public static final String STAFF_MDC_KEY = "staffId";
    public static final String HEADER = "X-Trace-Id";

    private TraceId() {
    }

    /** The current request's trace id, or {@code null} outside a request. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /** Set once authentication resolves (B03) so money mutations log who did them. */
    public static void putStaffId(String staffId) {
        if (staffId != null) {
            MDC.put(STAFF_MDC_KEY, staffId);
        }
    }
}

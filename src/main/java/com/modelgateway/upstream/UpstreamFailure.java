package com.modelgateway.upstream;

/**
 * A classified upstream failure. The classification, not the raw status code, decides what the
 * gateway does next.
 */
public final class UpstreamFailure extends RuntimeException {

    public enum Kind {
        /** Never reached the upstream. Safe to fail over: nothing was processed or billed. */
        CONNECT_FAILURE(true, true, false),
        RATE_LIMITED(true, true, false),
        SERVER_ERROR(true, true, false),
        /** 402/403/404: our account may not use this model here (region, arrears, entitlement). */
        UPSTREAM_REJECTED(true, false, false),
        /** 401: the provider key is bad. Try the next key of the same provider. */
        UNAUTHORIZED(false, false, false),
        /** The caller's request is invalid; another upstream would reject it too. */
        BAD_REQUEST(false, false, false),
        /** The request cannot be expressed in this upstream's protocol. No call was made. */
        INCOMPATIBLE(true, false, false),
        /**
         * The upstream may have processed (and billed) the request but no answer arrived. Retrying
         * would risk paying twice, so the attempt is booked at an estimate and the request ends.
         */
        READ_TIMEOUT(false, true, true),
        TRANSPORT_ERROR(false, true, true),
        MALFORMED_RESPONSE(false, true, true);

        private final boolean failover;
        private final boolean countsAgainstUpstream;
        private final boolean billingUnknown;

        Kind(boolean failover, boolean countsAgainstUpstream, boolean billingUnknown) {
            this.failover = failover;
            this.countsAgainstUpstream = countsAgainstUpstream;
            this.billingUnknown = billingUnknown;
        }

        /** Whether the next candidate may be tried. */
        public boolean failover() {
            return failover;
        }

        /** Whether the circuit breaker treats this as upstream ill health. 4xx never does. */
        public boolean countsAgainstUpstream() {
            return countsAgainstUpstream;
        }

        public boolean billingUnknown() {
            return billingUnknown;
        }
    }

    private final Kind kind;
    private final Integer httpStatus;

    public UpstreamFailure(Kind kind, Integer httpStatus, String message) {
        this(kind, httpStatus, message, null);
    }

    public UpstreamFailure(Kind kind, Integer httpStatus, String message, Throwable cause) {
        super(message, cause, false, false);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    public Kind kind() {
        return kind;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public static Kind classifyStatus(int status) {
        return switch (status) {
            case 401 -> Kind.UNAUTHORIZED;
            case 402, 403, 404 -> Kind.UPSTREAM_REJECTED;
            case 408, 429 -> Kind.RATE_LIMITED;
            default -> status >= 500 ? Kind.SERVER_ERROR : Kind.BAD_REQUEST;
        };
    }
}

package com.slg.matchbook.storage;

/**
 * Simple health check result used by /matchbook test.
 */
public record HealthCheckResult(boolean ok, String message) {
    public static HealthCheckResult ok(String message) {
        return new HealthCheckResult(true, message);
    }

    public static HealthCheckResult fail(String message) {
        return new HealthCheckResult(false, message);
    }
}

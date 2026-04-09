package com.amazon.product.filter;

/**
 * Static state holder for failure simulation.
 * Shared between CircuitBreakerTestController and CircuitBreakerTestFilter.
 *
 * Thread-safe using volatile keyword.
 */
public class CircuitBreakerTestState {

    private static volatile boolean simulatingFailure = false;

    public static void setSimulatingFailure(boolean value) {
        simulatingFailure = value;
    }

    public static boolean isSimulatingFailure() {
        return simulatingFailure;
    }

    /**
     * Reset to normal operation.
     * Called automatically on application startup.
     */
    public static void reset() {
        simulatingFailure = false;
    }
}

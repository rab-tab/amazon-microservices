package com.amazon.user.filter;

/**
 * Circuit Breaker Test State
 *
 * Thread-safe state management for circuit breaker testing
 */
public class CircuitBreakerTestState {

    private static volatile boolean simulatingFailure = false;
    private static volatile boolean testModeEnabled = false;

    // ✅ ADD: Setter for simulatingFailure
    public static void setSimulatingFailure(boolean value) {
        simulatingFailure = value;
        if (value) {
            testModeEnabled = true;  // Auto-enable test mode when simulating failure
        }
    }

    // ✅ ADD: Setter for testModeEnabled
    public static void setTestModeEnabled(boolean value) {
        testModeEnabled = value;
        if (!value) {
            simulatingFailure = false;  // Auto-disable simulation when disabling test mode
        }
    }

    // Existing methods
    public static void startSimulation() {
        testModeEnabled = true;
        simulatingFailure = true;
    }

    public static void stopSimulation() {
        simulatingFailure = false;
        testModeEnabled = false;
    }

    public static boolean isSimulatingFailure() {
        return simulatingFailure;
    }

    public static boolean isTestModeEnabled() {
        return testModeEnabled;
    }

    public static void reset() {
        simulatingFailure = false;
        testModeEnabled = false;
    }
}
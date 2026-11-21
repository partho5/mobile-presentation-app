package com.jovoc.facecampresentationrecorder.tracker;

/**
 * Names of the ApplicationExitInfo reasons. Pure Java: the REASON_* values are the fixed API
 * constants, written as literals so this class can be unit-tested.
 */
public final class ExitReasonNames {

    private ExitReasonNames() { }

    /** The name for an ApplicationExitInfo.REASON_* value. Unknown values give "UNKNOWN". */
    public static String reasonName(int reason) {
        switch (reason) {
            case 1: return "EXIT_SELF";
            case 2: return "SIGNALED";
            case 3: return "LOW_MEMORY";
            case 4: return "CRASH";
            case 5: return "CRASH_NATIVE";
            case 6: return "ANR";
            case 7: return "INITIALIZATION_FAILURE";
            case 8: return "PERMISSION_CHANGE";
            case 9: return "EXCESSIVE_RESOURCE_USAGE";
            case 10: return "USER_REQUESTED";
            case 11: return "USER_STOPPED";
            case 12: return "DEPENDENCY_DIED";
            case 13: return "OTHER";
            default: return "UNKNOWN";
        }
    }

    /** True for the exit reasons that are stored as errors rather than as plain app_exit activity. */
    public static boolean isError(String name) {
        return "ANR".equals(name) || "CRASH_NATIVE".equals(name) || "INITIALIZATION_FAILURE".equals(name);
    }
}

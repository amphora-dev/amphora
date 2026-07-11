package com.winlator.cmod.shared.android;

import android.content.Context;

/**
 * Stub (RFC §7): AppTerminationHelper is an app-layer (shared/android) Kotlin class
 * not ported to the engine kernel. AppUtils references only
 * {@code stopManagedServices(Context, String, boolean)}; this no-op stub satisfies
 * the compile. Real termination/service-cleanup logic lives in the amphora app shell.
 */
public final class AppTerminationHelper {
    private AppTerminationHelper() {}

    public static void stopManagedServices(Context context, String action, boolean restart) {
        // No-op stub (P1 compile).
    }
}

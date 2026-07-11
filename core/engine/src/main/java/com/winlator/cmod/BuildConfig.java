package com.winlator.cmod;

/**
 * Stub BuildConfig for the ported com.winlator.cmod kernel (RFC §7). Amphora's
 * real BuildConfig lives under app.amphora.* (engine namespace differs); this
 * satisfies the kernel's compile-time references. P1 compile-only.
 */
public final class BuildConfig {
    public static final boolean DEBUG = false;
    public static final String VERSION_NAME = "0.1.0";
    public static final int VERSION_CODE = 1;
    public static final String APPLICATION_ID = "com.winlator.cmod";
    public static final String BUILD_TYPE = "debug";
    private BuildConfig() {}
}

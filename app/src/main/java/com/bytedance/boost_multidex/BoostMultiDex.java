package com.bytedance.boost_multidex;

import android.content.Context;

/**
 * Compatibility stub for the BoostMultiDex call retained in WatchApplicationDelegate.
 *
 * The app installs AndroidX MultiDex itself after the delegate returns, so the
 * ByteDance startup optimization is intentionally disabled here.
 */
public final class BoostMultiDex {
    private BoostMultiDex() {
        // Utility class.
    }

    public static Result install(Context context) {
        return null;
    }

    public static boolean isOptimizeProcess(String processName) {
        return false;
    }
}

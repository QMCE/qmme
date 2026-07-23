package rj.qmme.ui;

import android.content.Context;

import com.tencent.qqnt.avatar.WatchAvatarView;

/**
 * The avatar class is Kotlin-metadata obfuscated in qq-core: Kotlin resolves a
 * synthetic default constructor that the shipped class does not contain.  A
 * Java call binds to the real four-argument JVM constructor instead.
 */
public final class WatchAvatarViewFactory {
    private WatchAvatarViewFactory() {
    }

    public static WatchAvatarView create(Context context) {
        return new WatchAvatarView(context, null, 0, 0);
    }
}

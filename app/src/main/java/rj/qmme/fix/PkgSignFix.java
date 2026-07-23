package rj.qmme.fix;

import android.content.Context;
import android.content.pm.Signature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Explicit package identity values used by QQ's WtLogin/MSF code.
 *
 * The app is installed as rj.qmme, but the embedded QQ Watch runtime was
 * built for com.tencent.qqlite.  Keeping these values in one small Java class
 * lets the bytecode patchers avoid depending on a context/package-manager
 * hook at the most sensitive login call sites.
 */
public final class PkgSignFix {
    private static final String ORIGIN_PACKAGE_NAME = AppSignatureCertificate.PACKAGE_NAME;
    private static final String ORIGIN_VERSION_NAME = "9.0.7";

    private PkgSignFix() {
    }

    public static Signature[] getOriginSignatures() {
        return new Signature[]{AppSignatureCertificate.createSignature()};
    }

    public static byte[] getApkId(Context context) {
        return ORIGIN_PACKAGE_NAME.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] getApkVersion(Context context, String packageName) {
        return ORIGIN_VERSION_NAME.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] getPkgSigFromApkName(Context context, String packageName) {
        try {
            return MessageDigest.getInstance("MD5")
                    .digest(AppSignatureCertificate.createSignature().toByteArray());
        } catch (Exception ignored) {
            return new byte[0];
        }
    }
}

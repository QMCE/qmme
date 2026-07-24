package rj.qmme.kernel;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

import com.tencent.qphone.base.util.BaseApplication;
import com.tencent.qphone.base.util.ROMUtil;
import com.tencent.qqnt.kernel.nativeinterface.DeviceInfo;

import mqq.app.AppRuntime;

/**
 * Project-owned equivalent of KernelSetterImpl.Companion.b().
 *
 * Keep this separate from the official injector: the values are obtained from
 * the runtime/device APIs directly, but the serialized field values follow the
 * Watch 9.0.7 native contract.
 */
final class ProjectKernelDeviceInfo {
    private static final String TAG = "QMME";
    private static final String CLIENT_BUILD = "9.0.7.2563";

    private ProjectKernelDeviceInfo() {}

    static DeviceInfo safeCreate(Context context, AppRuntime runtime) {
        try {
            return create(context, runtime);
        } catch (Throwable error) {
            Log.w(TAG, "ProjectKernelDeviceInfo: create failed", error);
            return new DeviceInfo();
        }
    }

    static DeviceInfo create(Context context, AppRuntime runtime) {

        String guid = readGuid(runtime, context);
        String buildVer = CLIENT_BUILD;
        int localId = java.util.Locale.getDefault().getLanguage().startsWith("zh") ? 0x804 : 0x409;
        String devType = valueOrEmpty(Build.DEVICE);
        String devName = valueOrEmpty(Build.MODEL);
        String vendorName = valueOrEmpty(runRomName());
        String osVer = valueOrEmpty(Build.VERSION.RELEASE);
        String vendorOsName = valueOrEmpty(runRomVersion());
        boolean setMute = isMuted(context);
        int vendorType = thirdPushType();
        // DeviceInfo's generated constructor is *not* ordered like the
        // official field-population sequence.  Its first two parameters are
        // guid/buildVer, followed by localId, devName, devType, vendorName,
        // osVer, vendorOsName, setMute and vendorType.  The previous code
        // passed the values in the official log order, which silently swapped
        // guid/buildVer/devType/devName/vendorName/osVer.  Native startup then
        // received a structurally valid object with semantically invalid
        // device fields and crashed later in TimerBase during start().
        // Populate through setters to make the ABI contract explicit and keep
        // it aligned with KernelSetterImpl.Companion.b().
        DeviceInfo info = new DeviceInfo();
        info.setGuid(guid);
        info.setBuildVer(buildVer);
        info.setLocalId(localId);
        info.setDevType(devType);
        info.setDevName(devName);
        info.setVendorName(vendorName);
        info.setOsVer(osVer);
        info.setVendorOsName(vendorOsName);
        info.setSetMute(setMute);
        info.setVendorType(vendorType);
        Log.d(TAG, "ProjectKernelDeviceInfo: ready guidLen=" + guid.length()
                + " buildVer=" + buildVer + " devType=" + devType
                + " devName=" + devName + " vendorName=" + vendorName
                + " osVer=" + osVer + " vendorOsName=" + vendorOsName
                + " localId=" + localId + " mute=" + setMute
                + " vendorType=" + vendorType);
        return info;
    }

    private static String readGuid(AppRuntime runtime, Context context) {
        // Do not call AppRuntime.getRuntimeService() from the native global
        // adapter callback. During initWithMobileConfig that can re-enter the
        // runtime service graph and recursively request getDeviceInfo(). The
        // official adapter already has GUID state cached before this callback;
        // KidInfoUtil is the project-side non-reentrant equivalent.
        try {
            String raw = com.tencent.mobileqq.utils.KidInfoUtil.getGuid(context);
            return valueOrEmpty(raw);
        } catch (Throwable error) {
            Log.w(TAG, "ProjectKernelDeviceInfo: GUID failed", error);
            return "";
        }
    }

    private static String runRomName() {
        try {
            return ROMUtil.getRomName();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String runRomVersion() {
        try {
            return ROMUtil.getRomVersion();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean isMuted(Context context) {
        try {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) return false;
            int volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            return volume == 0 || audio.getRingerMode() == AudioManager.RINGER_MODE_SILENT;
        } catch (Throwable error) {
            Log.w(TAG, "ProjectKernelDeviceInfo: audio state failed", error);
            return false;
        }
    }

    private static int thirdPushType() {
        try {
            BaseApplication app = BaseApplication.getContext();
            return app == null ? 0 : app.getThirdPushType();
        } catch (Throwable error) {
            Log.w(TAG, "ProjectKernelDeviceInfo: third push type failed", error);
            return 0;
        }
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}

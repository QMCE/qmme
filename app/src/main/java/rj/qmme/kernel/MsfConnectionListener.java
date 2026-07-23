package rj.qmme.kernel;

import android.util.Log;

import com.tencent.qqnt.kernel.api.IKernelService;
import com.tencent.qqnt.kernel.nativeinterface.MsfChangeReasonType;
import com.tencent.qqnt.kernel.nativeinterface.MsfStatusType;
import com.tencent.qqnt.watch.mainframe.api.IMsfConnPushListener;

import mqq.app.AppRuntime;

/**
 * JVM-ABI adapter for the stripped QQ Watch IMsfConnPushListener interface.
 * The Kotlin metadata exposes friendly names, but the actual interface
 * methods are a(), b(), c(), d().  Java keeps those exact method names so the
 * MSF servlet cannot dispatch into an AbstractMethodError.
 */
final class MsfConnectionListener implements IMsfConnPushListener {
    private static final String TAG = "QMME";
    private final AppRuntime runtime;

    MsfConnectionListener(AppRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public void a() {
        // onConnAllFailed
    }

    @Override
    public void b() {
        // onConnClose
        updateStatus(MsfStatusType.KDISCONNECTED);
    }

    @Override
    public void c() {
        // onConnOpen
        updateStatus(MsfStatusType.KCONNECTED);
    }

    @Override
    public void d() {
        // onConnWeakNet
    }

    private void updateStatus(MsfStatusType status) {
        try {
            IKernelService service = runtime.getRuntimeService(IKernelService.class, "");
            if (service != null) {
                service.setOnMsfStatusChanged(status, MsfChangeReasonType.KAUTO, 0);
                Log.d(TAG, "msfBridge: status=" + status);
            }
        } catch (Throwable error) {
            Log.w(TAG, "msfBridge: status=" + status + " failed", error);
        }
    }
}

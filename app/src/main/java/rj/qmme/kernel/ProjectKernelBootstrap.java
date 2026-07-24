package rj.qmme.kernel;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.tencent.qqnt.kernel.nativeinterface.AppSetting;
import com.tencent.qqnt.kernel.nativeinterface.AvifTranscodeInfo;
import com.tencent.qqnt.kernel.nativeinterface.AvifTranscodeResult;
import com.tencent.qqnt.kernel.nativeinterface.DeviceCodecFormatInfo;
import com.tencent.qqnt.kernel.nativeinterface.DeviceInfo;
import com.tencent.qqnt.kernel.nativeinterface.IGlobalAdapter;
import com.tencent.qqnt.kernel.nativeinterface.IOperateResult;
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperEngine;
import com.tencent.qqnt.kernel.nativeinterface.InitGlobalPathConfig;
import com.tencent.qqnt.kernel.nativeinterface.MarkdownParseReq;
import com.tencent.qqnt.kernel.nativeinterface.MarkdownParseRsp;
import com.tencent.qqnt.kernel.nativeinterface.OpentelemetryTracePlan;
import com.tencent.qqnt.kernel.nativeinterface.RichMediaImgSize;
import com.tencent.qqnt.kernel.nativeinterface.SendRequestParam;
import com.tencent.qqnt.kernel.nativeinterface.WrapperEngineGlobalConfig;
import com.tencent.qqnt.kernel.nativeinterface.MsfRspInfo;
import com.tencent.qqnt.kernel.nativeinterface.ThumbConfig;
import com.tencent.mobileqq.msf.core.NetConnInfoCenter;
import rj.qmme.diagnostics.OfflineDiagnostics;

import java.util.ArrayList;
import java.util.HashMap;

import mqq.app.AppRuntime;

/**
 * Project-owned equivalent of the small native-engine bootstrap performed by
 * KernelSetterImpl's generated companion.  It deliberately does not create or
 * invoke any QQ injector; it only uses the public Watch wrapper-engine API.
 */
final class ProjectKernelBootstrap {
    private static final String TAG = "QMME";
    private static final int APP_TYPE_WATCH = 7;
    private static final String CLIENT_VERSION = "9.0.7";
    private static final String CLIENT_BUILD = "9.0.7.2563";
    private static final String QUA = "V1_WAT_SQ_9.0.7_0_IDC_B";

    private ProjectKernelBootstrap() {}

    static boolean initialize(Context context, AppRuntime runtime) {
        if (context == null || runtime == null) {
            Log.e(TAG, "KernelBootstrap: context/runtime is null");
            return false;
        }
        try {
            IQQNTWrapperEngine engine = IQQNTWrapperEngine.CppProxy.get();
            if (engine == null) {
                Log.e(TAG, "KernelBootstrap: wrapper engine is null");
                return false;
            }

            Context appContext = context.getApplicationContext();
            String root = appContext.getFilesDir().getParentFile().getPath();
            InitGlobalPathConfig paths = new InitGlobalPathConfig(
                    "",
                    root + "/databases/",
                    root + "/Tencent/QQfile_recv/",
                    root + "/.runtimetmp/",
                    ""
            );
            // The official Watch InitialModuleInjector only supplies appType
            // and the path config here. Do not invent appVersion/platform/QUA
            // fields: the native wrapper treats their zero/default values as
            // part of its mobile bootstrap contract.
            WrapperEngineGlobalConfig config = new WrapperEngineGlobalConfig();
            config.appType = APP_TYPE_WATCH;
            config.globalPathConfig = paths;

            // Match the official mobile wrapper's release log level without
            // enabling debug-only behavior in the application layer.
            engine.setLogLevel(0);
            OfflineDiagnostics.INSTANCE.record(
                    appContext,
                    "wrapper_bootstrap_enter",
                    "runtimeIdentity=" + System.identityHashCode(runtime)
            );
            boolean ok = engine.initWithMobileConfig(config, new ProjectGlobalAdapter(appContext, runtime, engine));
            String details = "runtimeIdentity=" + System.identityHashCode(runtime) + " result=" + ok;
            Log.i(TAG, "KernelBootstrap: initWithMobileConfig=" + details + " root=" + root);
            OfflineDiagnostics.INSTANCE.record(appContext, "wrapper_bootstrap_returned", details);
            return ok;
        } catch (Throwable error) {
            Log.e(TAG, "KernelBootstrap: native engine initialization failed", error);
            return false;
        }
    }

    private static final class ProjectGlobalAdapter implements IGlobalAdapter {
        private final Context context;
        private final AppRuntime runtime;
        private final DeviceInfo deviceInfo;
        private final IQQNTWrapperEngine engine;

        ProjectGlobalAdapter(Context context, AppRuntime runtime, IQQNTWrapperEngine engine) {
            this.context = context;
            this.runtime = runtime;
            this.engine = engine;
            this.deviceInfo = ProjectKernelDeviceInfo.safeCreate(context, runtime);
        }

        @Override public AvifTranscodeResult avifTranscodeJpgAndGenAIOThumb(int type, AvifTranscodeInfo info) {
            return new AvifTranscodeResult();
        }
        @Override public Integer fixPicImgType(String path) { return -1; }
        @Override public Boolean generatePicAioThumb(String source, String target) { return Boolean.TRUE; }
        @Override public Boolean generateThumb(String source, String target, int width, int height) { return Boolean.TRUE; }
        @Override public Integer getActiveIPStackType() { return 3; }
        @Override public AppSetting getAppSetting() {
            return new AppSetting(
                    com.tencent.qphone.base.util.QLog.isColorLevel(),
                    false,
                    true,
                    true
            );
        }
        @Override public DeviceCodecFormatInfo getDeviceCodecFormatInfo() { return new DeviceCodecFormatInfo(); }
        @Override public DeviceInfo getDeviceInfo() {
            // Keep the field order identical to the official Watch adapter:
            // buildVer, devName, localId, devType, guid, osVer, vendorName,
            // vendorOsName, setMute, vendorType.  Passing these in a guessed
            // order makes native device serialization subtly invalid.
            return deviceInfo;
        }
        @Override public Integer getMSFUsedIpProtocolType() { return 0; }
        @Override public RichMediaImgSize getRichMediaImgSize(String path, int type) { return new RichMediaImgSize(0, 0); }
        @Override public IOperateResult onCompressVideo(String source, String target) { return null; }
        @Override public void onDataReport(String category, String event, boolean realtime, HashMap<String, String> data, boolean encrypted) { }
        @Override public void onDataReportWithAppKey(String appKey, String category, String event, boolean realtime, HashMap<String, String> data, boolean encrypted) { }
        @Override public OpentelemetryTracePlan onGetMqqOpentelemetryTraceReportPlan() { return null; }
        @Override public void onGetOfflineMsg() { }
        @Override public Long onGetSrvCalTime() {
            try {
                return NetConnInfoCenter.getServerTimeMillis();
            } catch (Throwable error) {
                Log.w(TAG, "KernelBootstrap: server time unavailable", error);
                return System.currentTimeMillis();
            }
        }
        @Override public void onInstallFinished(boolean success) { Log.d(TAG, "KernelBootstrap: native install finished=" + success); }
        @Override public void onLog(int level, String message) {
            if (message != null && level <= 1) Log.d(TAG, "KernelNative: " + message);
        }
        @Override public MarkdownParseRsp onParseMarkdown(MarkdownParseReq request) { return new MarkdownParseRsp(); }
        @Override public void onRegisterCountInstruments(ArrayList<String> names, int type, int source) { }
        @Override public void onRegisterValueInstruments(ArrayList<String> names, ArrayList<Double> values, int type, int source) { }
        @Override public void onReportCountIndicators(HashMap<String, String> data, String name, long value) { }
        @Override public void onReportValueIndicators(HashMap<String, String> data, String name, double value) { }

        /**
         * Network transport is owned by the project MSF bridge.  Until that
         * bridge is explicitly wired, fail closed rather than handing native
         * startup a fabricated response or silently changing account state.
         */
        @Override public void onSendSSORequest(long seq, String command, byte[] body,
                                               SendRequestParam param, String service,
                                               HashMap<String, byte[]> ext, int commandType) {
            ProjectKernelTransport.sendEngineSso(
                    runtime,
                    engine,
                    seq,
                    command,
                    body,
                    param,
                    service,
                    ext,
                    commandType
            );
        }

        @Override public void onShowErrUITips(String message) { Log.w(TAG, "KernelNative UI tip: " + message); }
        @Override public void onUpdateGeneralFlag(int flag) { }
    }
}

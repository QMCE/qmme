package rj.qmme.kernel;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.tencent.mobileqq.inject.IAppSettingInject;
import com.tencent.qqnt.kernel.api.impl.KernelServiceImpl;
import com.tencent.qqnt.kernel.dependences.IAccountModule;
import com.tencent.qqnt.kernel.dependences.IBusinessModule;
import com.tencent.qqnt.kernel.dependences.IRelationModule;
import com.tencent.qqnt.kernel.dependences.ISenderModule;
import com.tencent.qqnt.kernel.nativeinterface.BatteryStatus;
import com.tencent.qqnt.kernelgpro.nativeinterface.GProInitSessionConfig;
import com.tencent.qqnt.kernelgpro.nativeinterface.IGProDependsAdapter;
import com.tencent.qqnt.kernelgpro.nativeinterface.IKernelGProSessionListener;
import com.tencent.qqnt.kernelgpro.nativeinterface.IQQGProWrapperSession;
import com.tencent.qqnt.kernel.nativeinterface.DeviceInfo;
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession;
import com.tencent.qqnt.kernel.nativeinterface.IpType;
import com.tencent.qqnt.kernel.nativeinterface.OnLineBusinessInfo;
import com.tencent.qqnt.kernel.nativeinterface.SendRequestParam;
import com.tencent.qqnt.kernel.nativeinterface.ServerAddress;
import com.tencent.qqnt.kernel.nativeinterface.SessionTicket;

import mqq.manager.Manager;
import mqq.manager.TicketManager;
import oicq.wlogin_sdk.request.Ticket;
import rj.qmme.diagnostics.OfflineDiagnostics;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import mqq.app.AppRuntime;

/** Project-owned dependency slots for the Watch KernelServiceImpl. */
final class ProjectKernelDependencies {
    private static final String TAG = "QMME";
    private static final int APP_ID = 537282233;
    private static final String CLIENT_VERSION = "9.0.7";
    private static final String CLIENT_BUILD = "9.0.7.2563";
    private static final String QUA = "V1_WAT_SQ_9.0.7_0_IDC_B";

    private ProjectKernelDependencies() {}

    static void install(KernelServiceImpl kernel, AppRuntime runtime) throws ReflectiveOperationException {
        set(kernel, "sAppSetting", new ProjectAppSetting(runtime.getApplicationContext()));
        set(kernel, "sRelationModule", ProjectRelationModule.INSTANCE);
        set(kernel, "sSenderModule", new ProjectSenderModule(runtime));
        set(kernel, "sBusinessModule", new ProjectBusinessModule(runtime));
        setStatic(KernelServiceImpl.class, "sAccountModule", new ProjectAccountModule());
        setStatic(KernelServiceImpl.class, "sAccountModuleList", new ArrayList<Class<?>>(List.of(ProjectAccountModule.class)));
        set(kernel, "onlineCallback", new ArrayList<>());
        Log.i(TAG, "KernelBridge: installed project-owned kernel dependencies");
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void setStatic(Class<?> owner, String name, Object value) throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    private static final class ProjectAppSetting implements IAppSettingInject {
        private final Context context;
        ProjectAppSetting(Context context) { this.context = context; }
        @Override public int a() {
            try {
                return context.getApplicationInfo().targetSdkVersion;
            } catch (Throwable ignored) {
                return Build.VERSION.SDK_INT;
            }
        }
        @Override public String c() { return CLIENT_VERSION; }
        @Override public String d(Context ignored) {
            int code = 1;
            try { code = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode; }
            catch (Throwable ignoredError) { }
            return CLIENT_VERSION + "." + code;
        }
        @Override public String e() { return CLIENT_BUILD; }
        @Override public boolean f(Context ignored) { return false; }
        @Override public boolean g(Context ignored) { return false; }
        @Override public int getAppId() { return APP_ID; }
        @Override public String getQUA() { return QUA; }
        @Override public String getQimei36() { return "needInjecQimei36"; }
        @Override public String getVersion() { return "android " + CLIENT_VERSION; }
        @Override public String h() { return "2563"; }
        @Override public void i(boolean ignored) { }
        @Override public boolean isDebugVersion() { return false; }
        @Override public boolean isGrayVersion() { return true; }
        @Override public boolean isPublicVersion() { return true; }
        @Override public boolean isUiTest() { return false; }
        @Override public String j() { return "V 9.0.7.2563"; }
        @Override public boolean k() { return false; }
    }

    private static final class ProjectRelationModule implements IRelationModule {
        static final ProjectRelationModule INSTANCE = new ProjectRelationModule();
        @Override public boolean a() { return true; }
    }

    /**
     * Project-owned equivalent of the Watch BusinessModuleInjector.  The
     * official module initializes the optional GPro wrapper between
     * IQQNTWrapperSession.init() and KernelServiceImpl.initService().  Leaving
     * this callback empty changes the native startup graph: startupSessionWrapper
     * still starts, but its gpro timer/scheduler is never attached.
     */
    private static final class ProjectBusinessModule implements IBusinessModule {
        private final AppRuntime runtime;
        private volatile IQQGProWrapperSession gproSession;

        ProjectBusinessModule(AppRuntime runtime) {
            this.runtime = runtime;
        }

        @Override public void a(HashMap<String, String> sessionMap) {
            Context context = runtime == null ? null : runtime.getApplicationContext();
            if (sessionMap == null || !sessionMap.containsKey("gpro")) {
                Log.d(TAG, "ProjectBusinessModule: no gpro session advertised");
                OfflineDiagnostics.INSTANCE.record(context, "gpro_init_skipped", "reason=no_session");
                return;
            }
            String sessionId = sessionMap.get("gpro");
            if (sessionId == null || sessionId.isEmpty()) {
                Log.w(TAG, "ProjectBusinessModule: gpro session id is empty");
                OfflineDiagnostics.INSTANCE.record(context, "gpro_init_skipped", "reason=empty_session");
                return;
            }
            try {
                IQQGProWrapperSession session =
                        IQQGProWrapperSession.CppProxy.getGProWrapperSession(sessionId);
                if (session == null) {
                    throw new IllegalStateException("GPro wrapper session is null");
                }
                // Match the 9.0.7 Watch module exactly.  Its GPro session
                // starts with an empty UID/ticket set; the main NT session
                // owns the account ticket lifecycle separately.
                GProInitSessionConfig config = new GProInitSessionConfig(
                        0L,
                        "",
                        "gpro_v1-6.db",
                        "",
                        "",
                        "",
                        ""
                );
                session.init(config, new IGProDependsAdapter() {}, new IKernelGProSessionListener() {
                    @Override public void onGProSessionCreate(int result, String createdSessionId) {
                        Log.d(TAG, "ProjectBusinessModule: gpro session result=" + result
                                + " hasSessionId=" + (createdSessionId != null && !createdSessionId.isEmpty()));
                        OfflineDiagnostics.INSTANCE.record(
                                context,
                                "gpro_session_created",
                                "result=" + result + " hasSessionId="
                                        + (createdSessionId != null && !createdSessionId.isEmpty())
                        );
                    }
                    @Override public void onGetSelfTinyId(long tinyId) {
                        Log.d(TAG, "ProjectBusinessModule: gpro self tiny id received");
                    }
                });
                gproSession = session;
                OfflineDiagnostics.INSTANCE.record(
                        context,
                        "gpro_init_requested",
                        "sessionIdPresent=true config=official_watch_9.0.7"
                );
            } catch (Throwable error) {
                Log.w(TAG, "ProjectBusinessModule: gpro initialization failed", error);
                OfflineDiagnostics.INSTANCE.record(
                        context,
                        "gpro_init_failed",
                        "error=" + error.getClass().getSimpleName()
                );
            }
        }

        @Override public void start() {
            Log.d(TAG, "ProjectBusinessModule: start");
        }

        @Override public void stop() {
            gproSession = null;
            Log.d(TAG, "ProjectBusinessModule: stop");
        }
    }

    private static final class ProjectAccountModule implements IAccountModule {
        @Override public void a(AppRuntime app, IQQNTWrapperSession session) { }
        @Override public OnLineBusinessInfo b(AppRuntime app) { return new OnLineBusinessInfo(0, 0, 0); }
        @Override public List<String> c(String uin, AppRuntime app) {
            TicketSnapshot t = TicketSnapshot.read(app, uin);
            return List.of(t.a2, t.d2, t.d2Key);
        }
        @Override public DeviceInfo d(AppRuntime app) {
            return ProjectKernelDeviceInfo.safeCreate(app.getApplicationContext(), app);
        }
        @Override public boolean e(AppRuntime app) {
            try {
                return app.getCurAccLoginType() != 5;
            } catch (Throwable error) {
                Log.w(TAG, "ProjectAccountModule: login type unavailable", error);
                return true;
            }
        }
    }

    private static final class ProjectSenderModule implements ISenderModule {
        private final AppRuntime runtime;
        ProjectSenderModule(AppRuntime runtime) { this.runtime = runtime; }

        private TicketManager ticketManager(AppRuntime owner) {
            try {
                Manager manager = owner.getManager(AppRuntime.TICKET_MANAGER);
                if (manager instanceof TicketManager) return (TicketManager) manager;
                Log.w(TAG, "ProjectSenderModule: runtime ticket manager type="
                        + (manager == null ? "null" : manager.getClass().getName()));
            } catch (Throwable error) {
                Log.w(TAG, "ProjectSenderModule: getManager(TICKET_MANAGER) failed", error);
            }
            return null;
        }

        private String hex(byte[] bytes) {
            if (bytes == null) return "";
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                String part = Integer.toHexString(b & 0xff);
                if (part.length() == 1) out.append('0');
                out.append(part);
            }
            return out.toString();
        }
        @Override public String a(String uin) {
            TicketManager ticketManager = ticketManager(runtime);
            if (ticketManager == null) {
                Log.w(TAG, "ProjectSenderModule: TicketManager unavailable for getA2");
                return "";
            }
            try {
                String a2 = ticketManager.getA2(uin);
                return a2 == null ? "" : a2;
            } catch (Throwable error) {
                Log.w(TAG, "ProjectSenderModule: getA2 failed", error);
                return "";
            }
        }
        @Override public String[] b() {
            try {
                String[] commands = runtime.getMessagePushSSOCommands();
                return commands == null ? new String[0] : commands;
            } catch (Throwable error) {
                Log.w(TAG, "ProjectSenderModule: message push command list unavailable", error);
                return new String[0];
            }
        }
        @Override public void c(long seq, String service, byte[] body, SendRequestParam param, String cmd, HashMap<String, byte[]> ext) { }
        @Override public BatteryStatus getBatteryStatus() { return new BatteryStatus(100, false); }
        @Override public ArrayList<ServerAddress> getIpDirectList(String host, IpType type) { return new ArrayList<>(); }
        @Override public SessionTicket getLoginTicket() {
            String uin = runtime.getAccount();
            TicketManager ticketManager = ticketManager(runtime);
            if (ticketManager == null || uin == null || uin.isEmpty()) {
                Log.w(TAG, "ProjectSenderModule: TicketManager unavailable for getLoginTicket");
                return new SessionTicket("", "", "");
            }

            String a2;
            try {
                a2 = ticketManager.getA2(uin);
            } catch (Throwable error) {
                Log.w(TAG, "ProjectSenderModule: getA2 failed", error);
                a2 = "";
            }
            if (a2 == null) a2 = "";

            String d2 = "";
            String d2Key = "";
            try {
                Ticket d2Ticket = ticketManager.getD2Ticket(uin);
                if (d2Ticket != null) {
                    d2 = hex(d2Ticket._sig);
                    d2Key = hex(d2Ticket._sig_key);
                }
            } catch (Throwable error) {
                Log.w(TAG, "ProjectSenderModule: getD2Ticket failed", error);
            }
            String readiness = "runtimeIdentity=" + System.identityHashCode(runtime)
                    + " a2Len=" + a2.length() + " d2Len=" + d2.length()
                    + " d2KeyLen=" + d2Key.length();
            Log.d(TAG, "ProjectSenderModule: login ticket readiness " + readiness);
            OfflineDiagnostics.INSTANCE.record(
                    runtime.getApplicationContext(),
                    "sender_login_ticket",
                    readiness
            );
            return new SessionTicket(a2, d2, d2Key);
        }
        @Override public void onSendNetRequest(long seq, String host, String path, HashMap<String, String> headers, HashMap<String, String> params, int retry, int timeout) { }
        @Override public void onSendOidbRequest(long seq, int service, int command, byte[] body, SendRequestParam param, String cmd, HashMap<String, byte[]> ext) { }
    }

    private static final class TicketSnapshot {
        final String a2, d2, d2Key;
        TicketSnapshot(String a2, String d2, String d2Key) { this.a2 = a2; this.d2 = d2; this.d2Key = d2Key; }
        boolean ready() { return !a2.isEmpty() && !d2.isEmpty() && !d2Key.isEmpty(); }
        static TicketSnapshot read(AppRuntime runtime, String uin) {
            try {
                Class<?> type = Class.forName("com.tencent.qqnt.account.login.api.ITicketRuntimeService");
                Object svc = runtime.getClass().getMethod("getRuntimeService", Class.class, String.class)
                        .invoke(runtime, type, "");
                String a2 = (String) type.getMethod("getA2", String.class).invoke(svc, uin);
                Object raw = type.getMethod("getLocalTicket", String.class, int.class).invoke(svc, uin, 262144);
                byte[] sig = raw == null ? null : (byte[]) raw.getClass().getField("_sig").get(raw);
                byte[] key = raw == null ? null : (byte[]) raw.getClass().getField("_sig_key").get(raw);
                return new TicketSnapshot(a2 == null ? "" : a2, hex(sig), hex(key));
            } catch (Throwable ignored) {
                return new TicketSnapshot("", "", "");
            }
        }
        private static String hex(byte[] bytes) {
            if (bytes == null) return "";
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        }
    }
}

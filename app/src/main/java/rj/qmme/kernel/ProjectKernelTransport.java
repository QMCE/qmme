package rj.qmme.kernel;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.util.Log;

import com.tencent.mobileqq.msf.sdk.AppNetConnInfo;
import com.tencent.qqnt.kernel.api.IKernelService;
import com.tencent.qqnt.kernel.msf.KernelECDHServlet;
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperEngine;
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession;
import com.tencent.qqnt.kernel.nativeinterface.MsfRspInfo;
import com.tencent.qqnt.kernel.nativeinterface.SendRequestParam;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import mqq.app.AppRuntime;
import mqq.app.NewIntent;
import mqq.observer.BusinessObserver;
import rj.qmme.diagnostics.OfflineDiagnostics;

/**
 * Project-owned transport adapter for the Watch kernel.
 *
 * The native engine does not talk to sockets directly.  It emits callbacks to
 * the Java layer, which must turn them into the normal AppRuntime/MSF servlet
 * flow and feed the reply back to the exact wrapper object that issued the
 * request.  This class deliberately uses the public servlet/runtime ABI and
 * does not instantiate any official injector.
 */
final class ProjectKernelTransport {
    private static final String TAG = "QMME";
    private static final String NO_NETWORK = "目前没有网络，请稍后再试!";
    private static final int RESULT_NO_NETWORK = -101;
    private static final int DEFAULT_HTTP_TIMEOUT_MS = 30_000;

    private static final ExecutorService HTTP_EXECUTOR = Executors.newCachedThreadPool(
            new ThreadFactory() {
                private int nextId;

                @Override
                public synchronized Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "QMME-KernelHttp-" + (++nextId));
                    thread.setDaemon(true);
                    return thread;
                }
            }
    );

    private ProjectKernelTransport() {}

    /**
     * Bridge the wrapper-engine ECDH/SSO callback to the existing MSF servlet.
     * The official adapter uses KernelECDHServlet for this callback; we build
     * the request ourselves so the project owns the lifecycle and observer.
     */
    static void sendEngineSso(
            AppRuntime runtime,
            IQQNTWrapperEngine engine,
            long requestId,
            String command,
            byte[] body,
            SendRequestParam param,
            String traceInfo,
            HashMap<String, byte[]> transInfo,
            int ignoredCommandType
    ) {
        if (runtime == null || engine == null || command == null || command.isEmpty()) {
            Log.w(TAG, "KernelTransport: reject ECDH/SSO request; runtime/engine/command missing");
            return;
        }

        NewIntent request = new NewIntent(runtime.getApplicationContext(), KernelECDHServlet.class);
        request.withouLogin = true;
        request.putExtra("cmd", command);
        request.putExtra("data", body);
        // KernelECDHServlet expects type=1 for engine SSO replies.
        request.putExtra("type", 1);
        request.putExtra("requestId", requestId);
        putStringTransInfo(request, transInfo);
        putTraceInfo(request, traceInfo);
        putRequestOptions(request, param, true);

        EngineReplyObserver observer = new EngineReplyObserver(engine, runtime, command);
        request.setObserver(observer);

        OfflineDiagnostics.INSTANCE.record(
                runtime.getApplicationContext(),
                "transport_engine_sso_send",
                "seq=" + requestId + " command=" + command
        );

        if (hasNoNetworkOption(param) && !AppNetConnInfo.isNetSupport()) {
            deliverNoNetwork(request, observer);
            return;
        }

        try {
            runtime.startServlet(request);
        } catch (Throwable error) {
            Log.e(TAG, "KernelTransport: start KernelECDHServlet failed command=" + command, error);
            OfflineDiagnostics.INSTANCE.record(
                    runtime.getApplicationContext(),
                    "transport_engine_sso_failed",
                    "seq=" + requestId + " error=" + error.getClass().getSimpleName()
            );
        }
    }

    /**
     * Execute a wrapper HTTP request and return the result through
     * IQQNTWrapperSession.onNetReply().  This follows the official Watch
     * semantics: HTTP 200 maps to wrapper result 0; non-200 and exceptions are
     * retried according to retryCnt, then returned as an error.
     */
    static void sendNetRequest(
            AppRuntime runtime,
            long requestId,
            String httpMethod,
            String url,
            HashMap<String, String> headers,
            HashMap<String, String> params,
            int retryCnt,
            int timeoutMs
    ) {
        if (runtime == null || url == null || url.isEmpty()) {
            Log.w(TAG, "KernelTransport: reject HTTP request; runtime/url missing seq=" + requestId);
            return;
        }

        IQQNTWrapperSession session = resolveWrapperSession(runtime);
        if (session == null) {
            Log.w(TAG, "KernelTransport: wrapper session unavailable for HTTP seq=" + requestId);
            OfflineDiagnostics.INSTANCE.record(
                    runtime.getApplicationContext(),
                    "transport_net_rejected",
                    "seq=" + requestId + " reason=wrapper_missing"
            );
            return;
        }

        final int safeRetry = Math.max(0, retryCnt);
        final int safeTimeout = timeoutMs > 0 ? timeoutMs : DEFAULT_HTTP_TIMEOUT_MS;
        final String method = httpMethod == null || httpMethod.isEmpty()
                ? "GET"
                : httpMethod.toUpperCase(Locale.US);

        OfflineDiagnostics.INSTANCE.record(
                runtime.getApplicationContext(),
                "transport_net_send",
                "seq=" + requestId + " method=" + method + " retry=" + safeRetry
        );
        HTTP_EXECUTOR.execute(() -> performHttpRequest(
                runtime,
                session,
                requestId,
                method,
                url,
                headers,
                params,
                safeRetry,
                safeTimeout
        ));
    }

    private static void performHttpRequest(
            AppRuntime runtime,
            IQQNTWrapperSession session,
            long requestId,
            String method,
            String url,
            HashMap<String, String> headers,
            HashMap<String, String> params,
            int retryCnt,
            int timeoutMs
    ) {
        HttpURLConnection connection = null;
        try {
            URL target = new URL(url);
            connection = (HttpURLConnection) target.openConnection();
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod(method);

            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        connection.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }
            }

            boolean hasBody = !"GET".equals(method) && !"HEAD".equals(method);
            if (hasBody) {
                JSONObject json = new JSONObject();
                if (params != null) {
                    for (Map.Entry<String, String> entry : params.entrySet()) {
                        if (entry.getKey() != null) {
                            json.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
                byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                if (connection.getRequestProperty("Content-Type") == null) {
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                }
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
            }

            int responseCode = connection.getResponseCode();
            String responseMessage = safeResponseMessage(connection);
            if (responseCode == HttpURLConnection.HTTP_OK) {
                byte[] responseBody = readResponse(connection.getInputStream());
                replyNet(runtime, session, requestId, 0, "", responseBody);
                OfflineDiagnostics.INSTANCE.record(
                        runtime.getApplicationContext(),
                        "transport_net_reply",
                        "seq=" + requestId + " result=0 bytes=" + responseBody.length
                );
                return;
            }

            if (retryCnt > 0) {
                closeQuietly(connection);
                performHttpRequest(
                        runtime,
                        session,
                        requestId,
                        method,
                        url,
                        headers,
                        params,
                        retryCnt - 1,
                        timeoutMs
                );
                return;
            }

            replyNet(runtime, session, requestId, responseCode, responseMessage, new byte[0]);
            OfflineDiagnostics.INSTANCE.record(
                    runtime.getApplicationContext(),
                    "transport_net_reply",
                    "seq=" + requestId + " result=" + responseCode
            );
        } catch (Throwable error) {
            if (retryCnt > 0) {
                performHttpRequest(
                        runtime,
                        session,
                        requestId,
                        method,
                        url,
                        headers,
                        params,
                        retryCnt - 1,
                        timeoutMs
                );
                return;
            }
            String message = error.getMessage();
            if (message == null || message.isEmpty()) {
                message = error.getClass().getSimpleName();
            }
            replyNet(runtime, session, requestId, -1, message, new byte[0]);
            OfflineDiagnostics.INSTANCE.record(
                    runtime.getApplicationContext(),
                    "transport_net_failed",
                    "seq=" + requestId + " error=" + error.getClass().getSimpleName()
            );
            Log.w(TAG, "KernelTransport: HTTP request failed seq=" + requestId, error);
        } finally {
            closeQuietly(connection);
        }
    }

    private static void replyNet(
            AppRuntime runtime,
            IQQNTWrapperSession session,
            long requestId,
            int result,
            String message,
            byte[] body
    ) {
        try {
            session.onNetReply(requestId, result, message == null ? "" : message, body == null ? new byte[0] : body);
        } catch (Throwable error) {
            Log.w(TAG, "KernelTransport: wrapper onNetReply failed seq=" + requestId, error);
            if (runtime != null) {
                OfflineDiagnostics.INSTANCE.record(
                        runtime.getApplicationContext(),
                        "transport_net_reply_failed",
                        "seq=" + requestId + " error=" + error.getClass().getSimpleName()
                );
            }
        }
    }

    private static IQQNTWrapperSession resolveWrapperSession(AppRuntime runtime) {
        try {
            Object service = runtime.getRuntimeService(IKernelService.class, "");
            if (service instanceof IKernelService) {
                return ((IKernelService) service).getWrapperSession();
            }
        } catch (Throwable error) {
            Log.w(TAG, "KernelTransport: get wrapper session failed", error);
        }
        return null;
    }

    private static void putStringTransInfo(NewIntent request, HashMap<String, byte[]> transInfo) {
        if (transInfo == null || transInfo.isEmpty()) return;
        HashMap<String, String> values = new HashMap<>();
        for (Map.Entry<String, byte[]> entry : transInfo.entrySet()) {
            if (entry.getKey() != null) {
                values.put(entry.getKey(), new String(
                        entry.getValue() == null ? new byte[0] : entry.getValue(),
                        StandardCharsets.UTF_8
                ));
            }
        }
        request.putExtra("trans_info", values);
    }

    private static void putTraceInfo(NewIntent request, String traceInfo) {
        if (traceInfo != null && !traceInfo.isEmpty()) {
            request.putExtra("trace_info", traceInfo);
        }
    }

    private static boolean hasNoNetworkOption(SendRequestParam param) {
        return param != null && (param.sendOptions & 1) != 0;
    }

    private static void putRequestOptions(NewIntent request, SendRequestParam param, boolean engineRequest) {
        if (param == null) return;
        if ((param.sendOptions & 2) != 0) {
            request.putExtra("extra_send_without_resend", true);
        }
        if ((param.sendOptions & 4) != 0) {
            request.putExtra("extra_no_need_answer", true);
        }
        if (param.sendTimeout != 0 || param.sendTimeoutOnSlowNet != 0) {
            boolean wifi = AppNetConnInfo.isWifiConn();
            int perTry = wifi ? param.sendTimeout : param.sendTimeoutOnSlowNet;
            int total = (param.resendNum + 1) * perTry;
            if (total > 0) {
                request.putExtra("timeout", (long) total);
            }
            request.putExtra("resend_num", param.resendNum);
        }
        request.putExtra("req_target_account_type", param.reqTargetAccountType);
        request.putExtra("extra_uin_type", Byte.valueOf((byte) param.accountType));
        request.putExtra("extra_uin", param.account);
    }

    private static void deliverNoNetwork(NewIntent request, BusinessObserver observer) {
        Bundle extras = request.getExtras();
        if (extras == null) extras = new Bundle();
        extras.putInt("result", RESULT_NO_NETWORK);
        extras.putString("data_error_msg", NO_NETWORK);
        observer.onReceive(1, false, extras);
    }

    private static byte[] readResponse(InputStream input) throws Exception {
        if (input == null) return new byte[0];
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = source.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String safeResponseMessage(HttpURLConnection connection) {
        try {
            String message = connection.getResponseMessage();
            return message == null ? "" : message;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void closeQuietly(HttpURLConnection connection) {
        if (connection == null) return;
        try {
            connection.disconnect();
        } catch (Throwable ignored) {
        }
    }

    private static final class EngineReplyObserver implements BusinessObserver {
        private final IQQNTWrapperEngine engine;
        private final AppRuntime runtime;
        private final String requestCommand;

        EngineReplyObserver(IQQNTWrapperEngine engine, AppRuntime runtime, String requestCommand) {
            this.engine = engine;
            this.runtime = runtime;
            this.requestCommand = requestCommand;
        }

        @Override
        public void onReceive(int type, boolean success, Bundle bundle) {
            if (bundle == null) return;
            long requestId = bundle.getLong("requestId", 0L);
            int result = bundle.getInt("result", 0);
            String error = bundle.getString("data_error_msg", success ? "" : NO_NETWORK);
            String command = bundle.getString("cmd", requestCommand);
            MsfRspInfo info = buildRspInfo(bundle, result, error);
            try {
                engine.onSendSSOReply(requestId, command == null ? requestCommand : command, result, error, info);
                OfflineDiagnostics.INSTANCE.record(
                        runtime.getApplicationContext(),
                        "transport_engine_sso_reply",
                        "seq=" + requestId + " result=" + result
                );
            } catch (Throwable callbackError) {
                Log.w(TAG, "KernelTransport: engine SSO reply failed seq=" + requestId, callbackError);
                OfflineDiagnostics.INSTANCE.record(
                        runtime.getApplicationContext(),
                        "transport_engine_sso_reply_failed",
                        "seq=" + requestId + " error=" + callbackError.getClass().getSimpleName()
                );
            }
        }
    }

    private static MsfRspInfo buildRspInfo(Bundle bundle, int result, String error) {
        byte[] body = bundle.getByteArray("bytes_bodybuffer");
        if (body == null) body = new byte[0];
        int trpcResult = bundle.getInt("trpc_result", 0);
        int trpcFuncResult = bundle.getInt("trpc_func_result", 0);
        HashMap<String, byte[]> transInfo = new HashMap<>();
        Serializable serializable = bundle.getSerializable("trans_info");
        if (serializable instanceof HashMap) {
            HashMap<?, ?> source = (HashMap<?, ?>) serializable;
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (entry.getKey() instanceof String && entry.getValue() instanceof byte[]) {
                    transInfo.put((String) entry.getKey(), (byte[]) entry.getValue());
                }
            }
        }
        return new MsfRspInfo(result, trpcResult, trpcFuncResult, error == null ? "" : error, body, transInfo);
    }
}

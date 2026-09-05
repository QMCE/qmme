package rj.qmme.data.ai

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import rj.qmme.data.AiSettings
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Streaming OpenAI-compatible chat/completions client that summarizes chat
 * transcripts (phone-side port of QMCE's MessageSummaryClient, with the
 * endpoint resolved from [AiSettings] instead of the watch settings store).
 */
class MessageSummaryClient {

    data class SummaryMessage(
        val sender: String,
        val text: String,
    )

    interface Listener {
        fun onChunk(text: String)
        fun onComplete()
        fun onError(message: String, retryable: Boolean)
    }

    private data class Endpoint(
        val url: String,
        val model: String,
        val apiKey: String?,
    )

    private companion object {
        const val TAG = "QMME-AI"
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 120_000
        const val MAX_MESSAGE_COUNT = 120
        const val MAX_MESSAGE_LENGTH = 2_000
        const val MAX_PROMPT_LENGTH = 80_000
    }

    fun stream(
        context: Context,
        messages: List<SummaryMessage>,
        listener: Listener,
    ): Request {
        val request = Request()
        val endpoint = AiSettings.resolve(context)?.let {
            Endpoint(url = it.baseUrl, model = it.model, apiKey = it.apiKey)
        }
        if (endpoint == null) {
            listener.onError("尚未配置 AI 端点，请在设置中填写", false)
            return request
        }
        val worker = Thread({ execute(request, messages, endpoint, listener) }, "QMME-AI-Summary")
            .apply { isDaemon = true }
        request.worker = worker
        worker.start()
        return request
    }

    private fun execute(
        request: Request,
        messages: List<SummaryMessage>,
        endpoint: Endpoint,
        listener: Listener,
    ) {
        if (messages.isEmpty()) {
            listener.onError("没有可总结的消息", false)
            return
        }
        var connection: HttpURLConnection? = null
        try {
            val body = buildRequestBody(messages, endpoint.model)
            connection = (URL(endpoint.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "text/event-stream, application/json")
                setRequestProperty("User-Agent", "QMME/1.0")
                endpoint.apiKey?.takeIf { it.isNotBlank() }?.let { key ->
                    setRequestProperty("Authorization", "Bearer $key")
                }
            }
            request.connection.set(connection)
            if (request.isCancelled()) return
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
                output.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                handleError(connection, responseCode, listener)
                return
            }

            var emitted = false
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                while (!request.isCancelled()) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank() || line.startsWith(":")) continue
                    val payload = if (line.startsWith("data:")) {
                        line.substringAfter("data:").trim()
                    } else {
                        line.trim()
                    }
                    if (payload.isBlank()) continue
                    if (payload == "[DONE]") break
                    emitted = consumePayload(payload, listener) || emitted
                }
            }
            if (!request.isCancelled()) {
                if (emitted) listener.onComplete()
                else listener.onError("模型未返回总结内容", true)
            }
        } catch (error: Exception) {
            if (request.isCancelled() || error is InterruptedException) return
            Log.w(TAG, "summary request failed", error)
            listener.onError("网络请求失败，请重试", true)
        } finally {
            request.connection.compareAndSet(connection, null)
            connection?.disconnect()
        }
    }

    private fun consumePayload(
        payload: String,
        listener: Listener,
    ): Boolean {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return false
        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return false
        val delta = choice.optJSONObject("delta")
        val message = choice.optJSONObject("message")
        val text = delta?.optString("content").orEmpty()
            .ifBlank { message?.optString("content").orEmpty() }
        if (text.isBlank()) return false
        listener.onChunk(text)
        return true
    }

    private fun buildRequestBody(messages: List<SummaryMessage>, model: String): String {
        val normalized = messages
            .takeLast(MAX_MESSAGE_COUNT)
            .map { message ->
                SummaryMessage(
                    sender = message.sender.ifBlank { "消息" }.take(80),
                    text = message.text.trim().take(MAX_MESSAGE_LENGTH),
                )
            }
            .filter { it.text.isNotBlank() }
        val transcript = buildString {
            normalized.forEachIndexed { index, message ->
                append(index + 1)
                append(". ")
                append(message.sender)
                append(": ")
                append(message.text.replace('\u0000', ' '))
                append('\n')
            }
        }.takeLast(MAX_PROMPT_LENGTH)
        val userPrompt = """
            请总结下面的 QQ 聊天记录。
            要求：使用中文；先给出一句话概括，再列出关键主题、重要结论、待办事项或未解决问题；不要编造记录中没有的信息；如果只是闲聊，直接概括主要内容即可。

            聊天记录（按时间顺序）：
            $transcript
        """.trimIndent()
        return JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "你是一个准确、克制的 QQ 聊天记录总结助手。")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            })
        }.toString()
    }

    private fun handleError(
        connection: HttpURLConnection,
        code: Int,
        listener: Listener,
    ) {
        val detail = runCatching {
            connection.errorStream?.bufferedReader()?.use { it.readText() }
        }.getOrNull().orEmpty()
        Log.w(TAG, "summary response code=$code body=${detail.take(240)}")
        when (code) {
            401, 403 -> listener.onError("AI 服务拒绝了请求", false)
            408, 429 -> listener.onError("AI 服务繁忙，请稍后重试", true)
            in 500..599 -> listener.onError("AI 服务暂时不可用，请重试", true)
            else -> listener.onError("AI 请求失败（$code）", true)
        }
    }

    class Request internal constructor() {
        internal val connection = AtomicReference<HttpURLConnection?>(null)
        private val cancelled = AtomicBoolean(false)
        @Volatile
        internal var worker: Thread? = null

        fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            connection.getAndSet(null)?.disconnect()
            worker?.interrupt()
        }

        internal fun isCancelled(): Boolean = cancelled.get()
    }
}

package com.xiaowu7z.cfipoptimizer.engine

import com.xiaowu7z.cfipoptimizer.IpSources
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.InterruptedIOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 1.2 核心探测引擎。
 *
 * 硬性约束：
 * 1. 指定 IP + SNI：URL 始终使用经过授权的测试主机；FixedDns 仅对该 hostname 返回指定 IP。
 * 2. 冷连接：每次 probe 新建 OkHttpClient + 空连接池。
 * 3. HTTP/1.1 强制。
 * 4. 分族：输入必须是 IP literal；remote 家族与 targetIp 等价判断全部基于 connectStart 捕获的
 *    真实 InetSocketAddress（InetAddress 字节比较），禁止字符串比较、禁止按 targetIp 推断。
 * 5. 最终测速只接受 2xx、有效 CF-RAY，以及足量且足时（或完整大样本）的响应。
 * 6. 取消：所有网络阶段（下载 + POP trace）的 Call 都绑定协程取消——停止按钮立即中断。
 * 7. 三口径吞吐：PayloadMbps（纯 body）/ CompleteTransferMbps（connectStart→bodyEnd）/
 *    CallTotalMbps（callStart→bodyEnd，含 DNS）。与 PS v3.0 的对照口径由真机 A/B 后确定，不预先声称一致。
 */
object ProbeEngine {

    const val SPEED_HOST = "speed.cloudflare.com"

    /**
     * A cancellation-aware registry closes a race where a second trace/WS call
     * is created immediately after the cancellation callback took its snapshot.
     * Calls registered after cancellation are cancelled synchronously.
     */
    private class ActiveCallRegistry {
        private val cancelled = AtomicBoolean(false)
        private val calls = CopyOnWriteArraySet<okhttp3.Call>()

        fun register(call: okhttp3.Call): Boolean {
            if (cancelled.get()) {
                call.cancel()
                return false
            }
            calls.add(call)
            if (cancelled.get()) {
                calls.remove(call)
                call.cancel()
                return false
            }
            return true
        }

        fun unregister(call: okhttp3.Call) {
            calls.remove(call)
        }

        fun cancelAll(): Int {
            cancelled.set(true)
            val snapshot = calls.toList()
            snapshot.forEach { it.cancel() }
            return snapshot.size
        }
    }

    /** Same cancellation-race protection for the direct TCP shortlist stage. */
    private class ActiveSocketRegistry {
        private val cancelled = AtomicBoolean(false)
        private val sockets = CopyOnWriteArraySet<Socket>()

        fun register(socket: Socket): Boolean {
            if (cancelled.get()) {
                try { socket.close() } catch (_: Exception) {}
                return false
            }
            sockets.add(socket)
            if (cancelled.get()) {
                sockets.remove(socket)
                try { socket.close() } catch (_: Exception) {}
                return false
            }
            return true
        }

        fun unregister(socket: Socket) {
            sockets.remove(socket)
        }

        fun cancelAll() {
            cancelled.set(true)
            sockets.forEach { try { it.close() } catch (_: Exception) {} }
        }
    }

    data class ProbeResult(
        val ok: Boolean,
        val error: String = "",
        val family: String = "",              // 从真实 socket 远端地址判断（非 targetIp 推断）
        val targetIp: String = "",
        val actualRemoteAddress: String = "",  // connectStart 捕获的真实 TCP 远端字符串
        val targetMatchesRemote: Boolean = false, // 字节级等价判断（InetAddress 比较，非字符串）
        val remoteIsIpv6: Boolean = false,     // 真实 socket 远端是否为 AF_INET6
        val sni: String = SPEED_HOST,
        val certHostname: String = "",
        val certVerified: Boolean = false,
        val httpCode: Int = 0,
        val httpVersion: String = "",
        val dnsMs: Double = -1.0,
        val tcpMs: Double = -1.0,
        val tlsMs: Double = -1.0,
        val ttfbMs: Double = -1.0,
        val bodyMs: Double = -1.0,
        val totalMs: Double = -1.0,
        val callTotalMs: Double = -1.0,
        val bytesDownloaded: Long = 0L,
        val bytesTarget: Long = 0L,
        val payloadMbps: Double = 0.0,          // 纯 Body 吞吐
        val completeTransferMbps: Double = 0.0, // 完整传输（connectStart→bodyEnd）
        val callTotalMbps: Double = 0.0,        // Call 总吞吐（callStart→bodyEnd，含 DNS）
        val colo: String = "",
        val loc: String = "",
        val events: String = ""
    )

    /**
     * Candidate compatibility proof for an Argo hostname. TLS hostname
     * verification is OkHttp's system default and is never disabled.
     */
    data class ArgoRouteResult(
        val ok: Boolean,
        val error: String = "",
        val targetIp: String = "",
        val sni: String = "",
        val hostHeader: String = "",
        val certVerified: Boolean = false,
        val targetMatchesRemote: Boolean = false,
        val actualRemoteAddress: String = "",
        val traceHttpCode: Int = 0,
        val wsPath: String = "",
        val wsHttpCode: Int = 0,
        val websocketAccepted: Boolean = false,
        val ttfbMs: Double = -1.0,
        val colo: String = "",
        val loc: String = ""
    )

    /** IP literal 校验：必须是 IPv4 或 IPv6 字面量，禁止 hostname。 */
    fun isIpLiteral(input: String): Boolean {
        if (input.isEmpty()) return false
        // IPv4：四段十进制，每段 0-255
        val v4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").matchEntire(input)
        if (v4 != null) {
            return v4.groupValues.drop(1).all { it.toIntOrNull() in 0..255 }
        }
        // IPv6：含 ':'（hostname 不可能含 ':'）+ 仅 hex/冒号/点字符 + 可解析为 Inet6Address
        if (input.contains(':')) {
            if (!input.all { it in "0123456789abcdefABCDEF:." }) return false
            return try {
                InetAddress.getByName(input) is Inet6Address
            } catch (e: Exception) {
                false
            }
        }
        return false
    }

    /** 返回 IP literal 的协议族；非法输入返回 null。 */
    fun familyOf(input: String): String? {
        if (!isIpLiteral(input)) return null
        return when (InetAddress.getByName(input)) {
            is Inet4Address -> "IPv4"
            is Inet6Address -> "IPv6"
            else -> null
        }
    }

    /** InetAddress 字节级等价判断（禁止字符串比较 IPv6）。 */
    fun addressesEqual(a: InetAddress?, b: InetAddress?): Boolean {
        if (a == null || b == null) return false
        return java.util.Arrays.equals(a.address, b.address)
    }

    /**
     * 约 1 秒真实下载测速所需的最大响应容量。所有策略至少请求 64 MB，
     * 避免高速线路在 800 ms 前读完较小响应而让不同模式得到不同结论；
     * 读取端仍会在一秒时间窗到达后主动关闭，并不会固定下载完整 64 MB。
     * [maximum] 保留用于兼容 1.0.0 内部调用。
     */
    @Suppress("UNUSED_PARAMETER")
    fun speedRequestBytes(expectedMbps: Int, maximum: Boolean = false): Long {
        val boundedMbps = expectedMbps.coerceIn(1, 2_000).toLong()
        val requested = boundedMbps * 125_000L * 3L / 2L
        return maxOf(64_000_000L, requested).coerceAtMost(256_000_000L)
    }

    data class SpeedRates(
        val payloadMbps: Double,
        val completeTransferMbps: Double,
        val callTotalMbps: Double
    )

    data class SpeedSampleAssessment(val ok: Boolean, val error: String = "")

    fun acceptedTcpAverageMs(rtts: List<Double>, requiredAttempts: Int = 3): Double? =
        rtts.takeIf {
            requiredAttempts > 0 && it.size == requiredAttempts &&
                it.all { value -> value > 0.0 && value.isFinite() }
        }?.average()

    fun calculateSpeedRates(
        downloadedBytes: Long,
        bodyMs: Double,
        completeMs: Double,
        callTotalMs: Double
    ): SpeedRates {
        fun rate(durationMs: Double): Double =
            if (downloadedBytes > 0L && durationMs > 0.0 && durationMs.isFinite()) {
                (downloadedBytes * 8 / 1_000_000.0) / (durationMs / 1_000.0)
            } else 0.0
        return SpeedRates(rate(bodyMs), rate(completeMs), rate(callTotalMs))
    }

    /** Pure acceptance policy, kept public for deterministic regression tests. */
    fun assessSpeedSample(
        httpCode: Int,
        handshakeOk: Boolean,
        remoteMatches: Boolean,
        rayColo: String,
        downloadedBytes: Long,
        requestedBytes: Long,
        bodyMs: Double,
        sampleMillis: Long
    ): SpeedSampleAssessment {
        val minimumWindowMs = minOf(800.0, sampleMillis.toDouble())
        val fullExpectedSample = requestedBytes >= 4_000_000L && downloadedBytes >= requestedBytes
        // A complete expected body of at least 4 MB is itself strong
        // target-bandwidth evidence. Requiring a minimum duration here would
        // incorrectly reject genuine high-throughput links that finish the
        // complete requested body well before one second. Tiny cached/error
        // responses cannot use this exception.
        val safeCompleteException = fullExpectedSample
        return when {
            httpCode !in 200..299 -> SpeedSampleAssessment(false, "HTTP $httpCode")
            !handshakeOk -> SpeedSampleAssessment(false, "TLS 握手失败")
            !remoteMatches -> SpeedSampleAssessment(false, "实际远端与候选 IP 不一致，结果已拒绝")
            rayColo.isEmpty() -> SpeedSampleAssessment(false, "响应缺少有效 CF-RAY，结果已拒绝")
            downloadedBytes < 128L * 1024L -> SpeedSampleAssessment(false, "下载样本不足：$downloadedBytes B")
            bodyMs < minimumWindowMs && !safeCompleteException -> SpeedSampleAssessment(
                false,
                "下载窗口过短：${"%.0f".format(Locale.ROOT, bodyMs)} ms"
            )
            else -> SpeedSampleAssessment(true)
        }
    }

    /**
     * Three direct TCP-connect RTTs for a bounded safe-public candidate. This is only a cheap shortlist stage:
     * it never makes an IP copyable and never replaces the strict TLS download
     * validation below.
     */
    suspend fun probeTcpRtt(
        targetIp: String,
        attempts: Int = 3,
        timeoutMillis: Int = 1_000,
        targetPort: Int = 443,
        log: (String) -> Unit = {}
    ): ProbeResult {
        val targetAddress = verifiedPublicTarget(targetIp)
            ?: return ProbeResult(ok = false, error = "候选必须是安全公网 IP", targetIp = targetIp)
        if (targetPort !in 1..65_535) return ProbeResult(ok = false, error = "端口无效", targetIp = targetIp)
        val boundedAttempts = attempts.coerceIn(1, 5)
        val boundedTimeout = timeoutMillis.coerceIn(250, 2_000)
        val activeSockets = ActiveSocketRegistry()
        val rtts = ArrayList<Double>(boundedAttempts)
        var result: ProbeResult? = null

        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation {
                activeSockets.cancelAll()
                log(">>> TCP 快筛已取消")
            }
            try {
                for (round in 0 until boundedAttempts) {
                    if (!cont.isActive) break
                    val socket = Socket()
                    if (!activeSockets.register(socket)) break
                    val started = System.nanoTime()
                    try {
                        socket.connect(InetSocketAddress(targetAddress, targetPort), boundedTimeout)
                        if (!socket.isConnected) throw java.net.ConnectException("未建立连接")
                        rtts.add((System.nanoTime() - started) / 1_000_000.0)
                    } finally {
                        activeSockets.unregister(socket)
                        try { socket.close() } catch (_: Exception) {}
                    }
                }
                val average = acceptedTcpAverageMs(rtts, boundedAttempts)
                if (average != null) {
                    result = ProbeResult(
                        ok = true,
                        family = if (targetAddress is Inet6Address) "IPv6" else "IPv4",
                        targetIp = targetIp,
                        actualRemoteAddress = targetAddress.hostAddress.orEmpty(),
                        targetMatchesRemote = true,
                        remoteIsIpv6 = targetAddress is Inet6Address,
                        tcpMs = average,
                        ttfbMs = average,
                        totalMs = rtts.sum(),
                        callTotalMs = rtts.sum(),
                        events = rtts.joinToString(prefix = "tcp-rtt=", separator = ",") { "%.1f".format(Locale.ROOT, it) }
                    )
                } else {
                    result = ProbeResult(ok = false, error = "TCP 快筛已取消", targetIp = targetIp)
                }
            } catch (e: Exception) {
                result = ProbeResult(
                    ok = false,
                    error = if (!cont.isActive) "已取消" else "TCP ${rtts.size + 1}/$boundedAttempts 失败：${e.javaClass.simpleName}",
                    targetIp = targetIp
                )
            } finally {
                activeSockets.cancelAll()
            }
            cont.resumeWith(Result.success(Unit))
        }
        return result ?: ProbeResult(ok = false, error = "TCP 快筛无结果", targetIp = targetIp)
    }

    /** 从 CF-RAY 的末段读取实际 Cloudflare POP，例如 xxx-HKG -> HKG。 */
    fun edgeColo(cfRay: String?): String {
        val suffix = cfRay.orEmpty().substringAfterLast('-', "").trim().uppercase(Locale.ROOT)
        return if (Regex("^[A-Z]{3}$").matches(suffix)) suffix else ""
    }

    /**
     * 固定候选 IP、严格 TLS/SNI/Host，并在约 1 秒下载窗口内测真实吞吐。
     * 必须同时通过真实 socket 对端匹配和 CF-RAY 校验，避免把重定向、
     * 系统代理或非 Cloudflare 响应误当成可填入节点的入口 IP。
     */
    suspend fun probeSpeedWindow(
        targetIp: String,
        requestedBytes: Long,
        sampleMillis: Long = 1_000L,
        timeoutSec: Int = 5,
        testHost: String = SPEED_HOST,
        targetPort: Int = 443,
        log: (String) -> Unit = {}
    ): ProbeResult {
        val targetAddress = verifiedPublicTarget(targetIp)
            ?: return ProbeResult(ok = false, error = "候选必须是安全公网 IP", targetIp = targetIp)
        val host = testHost.trim().lowercase(Locale.ROOT)
        if (host.isEmpty() || host.any { it.isWhitespace() }) {
            return ProbeResult(ok = false, error = "测试主机无效", targetIp = targetIp)
        }
        if (targetPort !in 1..65535) {
            return ProbeResult(ok = false, error = "端口无效", targetIp = targetIp)
        }

        val boundedBytes = requestedBytes.coerceIn(32_768L, 256_000_000L)
        val boundedWindow = sampleMillis.coerceIn(800L, 3_000L)
        val events = StringBuilder()
        var timing: ProbeTimingListener.Timings? = null
        var actualRemote: InetAddress? = null
        val client = newColdClient(
            host, targetIp, timeoutSec, events,
            { timing = it },
            { actualRemote = it },
            callTimeoutSec = timeoutSec
        )
        val request = Request.Builder()
            .url("https://${httpsAuthority(host, targetPort)}/__down?bytes=$boundedBytes")
            .header("Cache-Control", "no-store")
            .get()
            .build()
        val call = client.newCall(request)
        var result: ProbeResult? = null

        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation {
                call.cancel()
                log(">>> 1 秒下载测速已取消")
            }
            try {
                call.execute().use { response ->
                    var downloaded = 0L
                    val bodyStartNs = System.nanoTime()
                    val bodyDeadlineNs = bodyStartNs + boundedWindow * 1_000_000L
                    val body = response.body
                    if (body != null) {
                        val source = body.source()
                        val buffer = Buffer()
                        source.timeout().deadlineNanoTime(bodyDeadlineNs)
                        try {
                            while (downloaded < boundedBytes) {
                                if (System.nanoTime() >= bodyDeadlineNs) break
                                val wanted = minOf(256L * 1024L, boundedBytes - downloaded)
                                if (wanted <= 0L) break
                                val count = source.read(buffer, wanted)
                                if (count < 0L) break
                                downloaded += count
                                buffer.clear()
                            }
                        } catch (e: InterruptedIOException) {
                            // The per-body deadline is the intended end of a
                            // fixed window. A timeout substantially before it
                            // remains a real transport failure.
                            if (System.nanoTime() + 50_000_000L < bodyDeadlineNs) throw e
                            events.append("speed window deadline\n")
                        } finally {
                            try { source.timeout().clearDeadline() } catch (_: Exception) {}
                            try { source.close() } catch (_: Exception) {}
                        }
                    }
                    val bodyEndNs = System.nanoTime()
                    val bodyMs = ((bodyEndNs - bodyStartNs) / 1_000_000.0).coerceAtLeast(0.001)
                    val t = timing ?: ProbeTimingListener.Timings()
                    val remote = actualRemote ?: t.actualRemoteAddr
                    val remoteMatches = addressesEqual(targetAddress, remote)
                    val rayColo = edgeColo(response.header("CF-RAY"))
                    val handshakeOk = response.handshake != null
                    val assessment = assessSpeedSample(
                        httpCode = response.code,
                        handshakeOk = handshakeOk,
                        remoteMatches = remoteMatches,
                        rayColo = rayColo,
                        downloadedBytes = downloaded,
                        requestedBytes = boundedBytes,
                        bodyMs = bodyMs,
                        sampleMillis = boundedWindow
                    )
                    val completeMs = if (t.connectStartNs > 0L) {
                        ((bodyEndNs - t.connectStartNs) / 1_000_000.0).coerceAtLeast(bodyMs)
                    } else bodyMs
                    val callTotalMs = if (t.callStartNs > 0L) {
                        ((bodyEndNs - t.callStartNs) / 1_000_000.0).coerceAtLeast(completeMs)
                    } else completeMs
                    val rates = calculateSpeedRates(downloaded, bodyMs, completeMs, callTotalMs)
                    val remoteIsV6 = remote is Inet6Address
                    result = ProbeResult(
                        ok = assessment.ok,
                        error = assessment.error,
                        family = if (remote == null) "未知" else if (remoteIsV6) "IPv6" else "IPv4",
                        targetIp = targetIp,
                        actualRemoteAddress = remote?.hostAddress.orEmpty(),
                        targetMatchesRemote = remoteMatches,
                        remoteIsIpv6 = remoteIsV6,
                        sni = host,
                        certHostname = peerCn(response),
                        certVerified = handshakeOk,
                        httpCode = response.code,
                        httpVersion = response.protocol.toString(),
                        dnsMs = t.dnsMs(),
                        tcpMs = t.tcpMs(),
                        tlsMs = t.tlsMs(),
                        ttfbMs = t.ttfbMs(),
                        bodyMs = bodyMs,
                        totalMs = completeMs,
                        callTotalMs = callTotalMs,
                        bytesDownloaded = downloaded,
                        bytesTarget = boundedBytes,
                        payloadMbps = if (assessment.ok) rates.payloadMbps else 0.0,
                        completeTransferMbps = if (assessment.ok) rates.completeTransferMbps else 0.0,
                        callTotalMbps = if (assessment.ok) rates.callTotalMbps else 0.0,
                        colo = rayColo,
                        events = events.toString()
                    )
                }
                cont.resumeWith(Result.success(Unit))
            } catch (e: java.io.IOException) {
                result = ProbeResult(
                    ok = false,
                    error = if (cont.isCancelled) "已取消" else "${e.javaClass.simpleName}: ${e.message?.take(100)}",
                    targetIp = targetIp,
                    events = events.toString()
                )
                cont.resumeWith(Result.success(Unit))
            } catch (e: Exception) {
                result = ProbeResult(
                    ok = false,
                    error = "${e.javaClass.simpleName}: ${e.message?.take(100)}",
                    targetIp = targetIp,
                    events = events.toString()
                )
                cont.resumeWith(Result.success(Unit))
            }
        }
        return result ?: ProbeResult(ok = false, error = "no result", targetIp = targetIp)
    }

    /**
     * 单次冷连接下载探测（suspend：取消时中断 Call + trace Call）。
     */
    suspend fun probeDownload(
        targetIp: String,
        bytes: Long,
        timeoutSec: Int,
        testHost: String = SPEED_HOST,
        includeTrace: Boolean = true,
        targetPort: Int = 443,
        log: (String) -> Unit
    ): ProbeResult {
        if (verifiedPublicTarget(targetIp) == null) {
            return ProbeResult(ok = false, error = "候选必须是安全公网 IP", targetIp = targetIp)
        }
        val host = testHost.trim().lowercase(Locale.ROOT)
        if (host.isEmpty() || host.any { it.isWhitespace() }) {
            return ProbeResult(ok = false, error = "测试主机无效", targetIp = targetIp)
        }
        val events = StringBuilder()
        var timing: ProbeTimingListener.Timings? = null

        val client = newColdClient(host, targetIp, timeoutSec, events, { timing = it })
        if (targetPort !in 1..65535) {
            return ProbeResult(ok = false, error = "端口无效", targetIp = targetIp)
        }
        val authority = httpsAuthority(host, targetPort)
        val url = "https://$authority/__down?bytes=$bytes"
        val req = Request.Builder().url(url).get().build()
        val call = client.newCall(req)

        // 取消绑定：所有活跃 Call 统一在此取消
        val activeCalls = ActiveCallRegistry().apply { register(call) }

        var result: ProbeResult? = null
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation {
                val cancelled = activeCalls.cancelAll()
                log(">>> 取消触发：$cancelled 个 Call 已 cancel()")
            }
            try {
                try {
                    call.execute().use { resp ->
                    val bodyBytes = readBodyCounting(resp)
                    val t = timing ?: ProbeTimingListener.Timings()
                    // Phase 2.2.1：Pre/Micro/Baseline 不需要 POP。
                    // 只有最终 Full/显式 Debug 才做 trace，避免每个候选 IP 额外再建一次冷 TLS 连接。
                    val enoughBody = bytes <= 0L || bodyBytes >= (bytes * 0.8).toLong()
                    val rayColo = edgeColo(resp.header("CF-RAY"))
                    val (traceColo, loc) = if (includeTrace && resp.isSuccessful && enoughBody) {
                        try {
                            val traceCall = newTraceCall(host, targetIp, targetPort, timeoutSec, events)
                            if (!activeCalls.register(traceCall) || !cont.isActive) {
                                traceCall.cancel()
                                Pair("", "")
                            } else try {
                                executeTrace(traceCall, events)
                            } finally {
                                activeCalls.unregister(traceCall)
                            }
                        } catch (e: Exception) {
                            events.append("trace fail: ${e.javaClass.simpleName}\n")
                            Pair("", "")
                        }
                    } else {
                        Pair("", "")
                    }
                    val colo = traceColo.ifBlank { rayColo }
                    result = buildResult(targetIp, host, t, resp, bodyBytes, bytes, colo, loc, events.toString())
                    }
                } finally {
                    activeCalls.unregister(call)
                }
                cont.resumeWith(Result.success(Unit))
            } catch (e: java.io.IOException) {
                result = ProbeResult(
                    ok = false,
                    error = if (cont.isCancelled) "已取消" else "${e.javaClass.simpleName}: ${e.message?.take(100)}",
                    targetIp = targetIp,
                    events = events.toString()
                )
                cont.resumeWith(Result.success(Unit))
            } catch (e: Exception) {
                result = ProbeResult(
                    ok = false,
                    error = "${e.javaClass.simpleName}: ${e.message?.take(100)}",
                    targetIp = targetIp,
                    events = events.toString()
                )
                cont.resumeWith(Result.success(Unit))
            }
        }
        return result ?: ProbeResult(ok = false, error = "no result", targetIp = targetIp)
    }

    /**
     * Fixes [argoHost] to [targetIp], validates its certificate, reads the
     * Cloudflare trace endpoint and optionally sends a small WebSocket upgrade
     * request to [wsPath]. No origin download endpoint is assumed.
     */
    suspend fun probeArgoRoute(
        targetIp: String,
        argoHost: String,
        wsPath: String = "",
        targetPort: Int = 443,
        timeoutSec: Int = 8,
        log: (String) -> Unit = {}
    ): ArgoRouteResult {
        val targetAddress = verifiedPublicTarget(targetIp)
            ?: return ArgoRouteResult(
                ok = false,
                error = "候选必须是安全公网 IP",
                targetIp = targetIp,
                sni = argoHost,
                hostHeader = argoHost,
                wsPath = wsPath
            )
        val host = argoHost.trim().lowercase(Locale.ROOT)
        if (targetPort !in 1..65535) {
            return ArgoRouteResult(ok = false, error = "端口无效", targetIp = targetIp, sni = host, hostHeader = host, wsPath = wsPath)
        }
        val authority = httpsAuthority(host, targetPort)
        val events = StringBuilder()
        var traceTiming: ProbeTimingListener.Timings? = null
        val traceClient = newColdClient(host, targetIp, timeoutSec, events, { traceTiming = it })
        val traceCall = traceClient.newCall(
            Request.Builder()
                .url("https://$authority/cdn-cgi/trace")
                .header("Cache-Control", "no-store")
                .get()
                .build()
        )
        val activeCalls = ActiveCallRegistry().apply { register(traceCall) }
        var result: ArgoRouteResult? = null
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation {
                activeCalls.cancelAll()
                log(">>> Argo 兼容验证已取消")
            }
            try {
                var traceCode = 0
                var traceHandshake = false
                var traceText = ""
                try {
                    traceCall.execute().use { response ->
                        traceCode = response.code
                        traceHandshake = response.handshake != null
                        traceText = readTextLimited(response, 64 * 1024)
                    }
                } finally {
                    activeCalls.unregister(traceCall)
                }
                val timing = traceTiming ?: ProbeTimingListener.Timings()
                val remoteMatches = addressesEqual(targetAddress, timing.actualRemoteAddr)
                var colo = ""
                var loc = ""
                traceText.lineSequence().forEach { line ->
                    when {
                        line.startsWith("colo=") -> colo = line.removePrefix("colo=").trim()
                        line.startsWith("loc=") -> loc = line.removePrefix("loc=").trim()
                    }
                }

                var wsCode = 0
                var wsUpgrade = false
                var wsAcceptMatches = false
                var wsRemoteAddress: InetAddress? = null
                if (wsPath.isNotEmpty() && traceHandshake && remoteMatches) {
                    val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
                    val websocketKey = Base64.getEncoder().encodeToString(keyBytes)
                    val expectedAccept = Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-1")
                            .digest((websocketKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII))
                    )
                    val wsClient = newColdClient(host, targetIp, timeoutSec, events, {}, { wsRemoteAddress = it })
                    val wsCall = wsClient.newCall(
                        Request.Builder()
                            .url("https://$authority$wsPath")
                            .header("Connection", "Upgrade")
                            .header("Upgrade", "websocket")
                            .header("Sec-WebSocket-Version", "13")
                            .header("Sec-WebSocket-Key", websocketKey)
                            .header("Cache-Control", "no-store")
                            .get()
                            .build()
                    )
                    if (!activeCalls.register(wsCall) || !cont.isActive) {
                        wsCall.cancel()
                    } else try {
                        wsCall.execute().use { response ->
                            wsCode = response.code
                            wsUpgrade = response.header("Upgrade")?.equals("websocket", ignoreCase = true) == true &&
                                response.header("Connection").orEmpty().split(',').any { it.trim().equals("upgrade", ignoreCase = true) }
                            wsAcceptMatches = response.header("Sec-WebSocket-Accept") == expectedAccept
                            // Do not consume an upgraded stream; closing the response
                            // immediately keeps this a tiny compatibility check.
                        }
                    } catch (e: Exception) {
                        events.append("ws path: ").append(e.javaClass.simpleName).append('\n')
                    } finally {
                        activeCalls.unregister(wsCall)
                    }
                }
                val wsRemoteMatches = wsPath.isEmpty() || addressesEqual(targetAddress, wsRemoteAddress)
                val websocketAccepted = wsPath.isNotEmpty() && wsCode == 101 && wsUpgrade && wsAcceptMatches && wsRemoteMatches
                val pathCompatible = wsPath.isEmpty() || websocketAccepted
                val ok = traceHandshake && remoteMatches && traceCode in 200..499 && pathCompatible
                result = ArgoRouteResult(
                    ok = ok,
                    error = when {
                        !traceHandshake -> "TLS 证书验证失败"
                        !remoteMatches -> "实际远端与候选 IP 不一致"
                        traceCode !in 200..499 -> "Argo 域名入口返回 HTTP ${if (traceCode > 0) traceCode else "无响应"}"
                        !wsRemoteMatches -> "WS 实际远端与候选 IP 不一致"
                        !pathCompatible -> "WS 升级验证失败${if (wsCode > 0) "（HTTP $wsCode）" else ""}"
                        else -> ""
                    },
                    targetIp = targetIp,
                    sni = host,
                    hostHeader = host,
                    certVerified = traceHandshake,
                    targetMatchesRemote = remoteMatches,
                    actualRemoteAddress = timing.actualRemoteAddress,
                    traceHttpCode = traceCode,
                    wsPath = wsPath,
                    wsHttpCode = wsCode,
                    websocketAccepted = websocketAccepted,
                    ttfbMs = timing.ttfbMs(),
                    colo = colo,
                    loc = loc
                )
                cont.resumeWith(Result.success(Unit))
            } catch (e: java.io.IOException) {
                result = ArgoRouteResult(
                    ok = false,
                    error = if (cont.isCancelled) "已取消" else "${e.javaClass.simpleName}: ${e.message?.take(100)}",
                    targetIp = targetIp,
                    sni = host,
                    hostHeader = host,
                    wsPath = wsPath
                )
                cont.resumeWith(Result.success(Unit))
            } catch (e: Exception) {
                result = ArgoRouteResult(
                    ok = false,
                    error = "${e.javaClass.simpleName}: ${e.message?.take(100)}",
                    targetIp = targetIp,
                    sni = host,
                    hostHeader = host,
                    wsPath = wsPath
                )
                cont.resumeWith(Result.success(Unit))
            }
        }
        return result ?: ArgoRouteResult(ok = false, error = "no result", targetIp = targetIp, sni = host, hostHeader = host)
    }

    /** POP 查询（cdn-cgi/trace）——suspend + 可取消。 */
    suspend fun probeTrace(
        targetIp: String,
        timeoutSec: Int,
        testHost: String = SPEED_HOST,
        targetPort: Int = 443,
        log: (String) -> Unit
    ): Pair<String, String> {
        if (verifiedPublicTarget(targetIp) == null) return Pair("", "")
        val events = StringBuilder()
        val host = testHost.trim().lowercase(Locale.ROOT)
        val call = newTraceCall(host, targetIp, targetPort, timeoutSec, events)
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation {
                call.cancel()
                log(">>> trace Call.cancel() 已触发")
            }
            try {
                cont.resumeWith(Result.success(executeTrace(call, events)))
            } catch (e: Exception) {
                log("trace fail: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
                cont.resumeWith(Result.success(Pair("", "")))
            }
        }
    }

    // ---------- 内部 ----------

    /** 构造 trace call（不执行，不注册）。 */
    private fun newTraceCall(
        testHost: String,
        targetIp: String,
        targetPort: Int,
        timeoutSec: Int,
        events: StringBuilder
    ): okhttp3.Call {
        val client = newColdClient(testHost, targetIp, timeoutSec, events, {})
        val req = Request.Builder().url("https://${httpsAuthority(testHost, targetPort)}/cdn-cgi/trace").get().build()
        return client.newCall(req)
    }

    private fun httpsAuthority(host: String, port: Int): String =
        if (port == 443) host else "$host:$port"

    fun isSafePublicTarget(value: String): Boolean = verifiedPublicTarget(value) != null

    private fun verifiedPublicTarget(value: String): InetAddress? {
        if (!isIpLiteral(value)) return null
        val address = try { InetAddress.getByName(value) } catch (_: Exception) { return null }
        return address.takeIf { IpSources.isPublicAddress(it) }
    }

    /** 执行 trace call 并解析 colo/loc。 */
    private fun executeTrace(call: okhttp3.Call, events: StringBuilder): Pair<String, String> {
        return try {
            call.execute().use { resp ->
                val text = resp.body?.string() ?: ""
                var colo = ""; var loc = ""
                text.lineSequence().forEach { line ->
                    when {
                        line.startsWith("colo=") -> colo = line.removePrefix("colo=").trim()
                        line.startsWith("loc=") -> loc = line.removePrefix("loc=").trim()
                    }
                }
                Pair(colo, loc)
            }
        } catch (e: Exception) {
            events.append("trace fail: ${e.javaClass.simpleName}\n")
            Pair("", "")
        }
    }

    private fun newColdClient(
        testHost: String,
        targetIp: String,
        timeoutSec: Int,
        events: StringBuilder,
        onTimings: (ProbeTimingListener.Timings) -> Unit,
        onConnect: (InetAddress?) -> Unit = {},
        callTimeoutSec: Int = timeoutSec + 10
    ): OkHttpClient {
        val listener = ProbeTimingListener(
            onEvent = { events.append(it).append("\n") },
            onTimings = onTimings,
            onConnect = onConnect
        )
        return OkHttpClient.Builder()
            .dns(FixedDns.forTestHost(testHost, targetIp))
            .protocols(listOf(Protocol.HTTP_1_1))
            // Do not inherit a system HTTP proxy: the TCP peer must be the
            // authorized candidate IP captured by the fixed DNS mapping.
            .proxy(Proxy.NO_PROXY)
            // A redirect can change the hostname and therefore the destination.
            // This diagnostic intentionally measures only the authorized test
            // host's current DNS IP snapshot, so redirects are evidence only,
            // never followed.
            .followRedirects(false)
            .followSslRedirects(false)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
            .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .callTimeout(callTimeoutSec.coerceAtLeast(1).toLong(), TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .eventListener(listener)
            .build()
    }

    private fun readBodyCounting(resp: Response): Long {
        val body = resp.body ?: return 0L
        var count = 0L
        val buf = ByteArray(256 * 1024)
        body.byteStream().use { ins ->
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                count += n
            }
        }
        return count
    }

    private fun readTextLimited(resp: Response, maxBytes: Int): String {
        val body = resp.body ?: return ""
        body.byteStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                val accepted = minOf(count, maxBytes - total)
                if (accepted > 0) output.write(buffer, 0, accepted)
                total += accepted
                if (total >= maxBytes) break
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }

    private fun peerCn(resp: Response): String {
        return try {
            val cert = resp.handshake?.peerCertificates?.firstOrNull()
                as? java.security.cert.X509Certificate
            cert?.subjectX500Principal?.name ?: ""
        } catch (e: Exception) { "" }
    }

    private fun buildResult(
        targetIp: String,
        testHost: String,
        t: ProbeTimingListener.Timings,
        resp: Response,
        bytesDownloaded: Long,
        bytesTarget: Long,
        colo: String,
        loc: String,
        events: String
    ): ProbeResult {
        // 真实 socket 数据（connectStart 捕获的 InetAddress）
        val actualAddr = t.actualRemoteAddr
        val remoteIsV6 = actualAddr is Inet6Address
        val family = if (actualAddr != null) {
            if (remoteIsV6) "IPv6" else "IPv4"
        } else "未知"

        // 字节级等价判断（禁止字符串比较，IPv6 压缩形式不同也能正确判定）
        val targetAddr = try { InetAddress.getByName(targetIp) } catch (e: Exception) { null }
        val targetMatches = addressesEqual(targetAddr, actualAddr)

        // 80% 完整性规则
        val complete = bytesTarget <= 0L || bytesDownloaded >= (bytesTarget * 0.80).toLong()
        // Remote 必须与本轮候选 IP 字节级一致。若网络、中间件或
        // 代理将连接改指向其他地址，吞吐不计入任何 IP 成绩。
        val ok = resp.code in 200..399 && resp.handshake != null && complete && targetMatches

        // 三口径吞吐
        fun mbps(ms: Double): Double =
            if (ok && ms > 0.0) (bytesDownloaded * 8 / 1_000_000.0) / (ms / 1000.0) else 0.0

        return ProbeResult(
            ok = ok,
            error = when {
                resp.code !in 200..399 -> "HTTP ${resp.code}"
                !complete -> "下载不完整：$bytesDownloaded / $bytesTarget（<80%）"
                resp.handshake == null -> "TLS 握手失败"
                !targetMatches -> "实际远端与候选 IP 不一致，结果已拒绝"
                else -> ""
            },
            family = family,
            targetIp = targetIp,
            actualRemoteAddress = t.actualRemoteAddress,
            targetMatchesRemote = targetMatches,
            remoteIsIpv6 = remoteIsV6,
            sni = testHost,
            certHostname = peerCn(resp),
            certVerified = resp.handshake != null,
            httpCode = resp.code,
            httpVersion = resp.protocol.toString(),
            dnsMs = t.dnsMs(),
            tcpMs = t.tcpMs(),
            tlsMs = t.tlsMs(),
            ttfbMs = t.ttfbMs(),
            bodyMs = t.bodyMs(),
            totalMs = t.totalMs(),
            callTotalMs = t.callTotalMs(),
            bytesDownloaded = bytesDownloaded,
            bytesTarget = bytesTarget,
            payloadMbps = mbps(t.bodyMs()),
            completeTransferMbps = mbps(t.totalMs()),
            callTotalMbps = mbps(t.callTotalMs()),
            colo = colo,
            loc = loc,
            events = events
        )
    }
}

package com.xiaowu7z.cfipoptimizer.engine

import com.xiaowu7z.cfipoptimizer.IpSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okio.Buffer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.Locale
import java.util.Random
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.coroutineContext

/** Independent implementation of the reference application's observable scan flow. */
object ReferenceScanner {
    const val ROUND_SIZE = 100
    const val RTT_CONCURRENCY = 50
    const val RTT_ATTEMPTS = 3
    const val SPEED_SHORTLIST = 10
    const val SPEED_SECONDS = 5
    private const val CUSTOM_PER_ROUND = 20

    data class Candidate(val ip: String, val source: String)
    data class RttCandidate(val candidate: Candidate, val latencyMs: Int)
    data class Stage(val name: String, val current: Int, val total: Int, val detail: String = "")
    data class SpeedSample(
        val ok: Boolean,
        val peakKbps: Int = 0,
        val tcpMs: Int = 0,
        val colo: String = "",
        val bytesDownloaded: Long = 0L,
        val error: String = ""
    )
    data class Winner(
        val ip: String,
        val family: String,
        val source: String,
        val peakKbps: Int,
        val tcpMs: Int,
        val rttMs: Int,
        val colo: String,
        val dataCenter: String,
        val bytesDownloaded: Long,
        val round: Int,
        val useTls: Boolean,
        val speedHost: String,
        val route: ProbeEngine.ArgoRouteResult? = null
    ) {
        val realMbps: Double get() = peakKbps / 128.0
    }

    fun buildRoundCandidates(
        ranges: Collection<String>,
        customIps: Collection<String>,
        family: String,
        random: Random,
        limit: Int = ROUND_SIZE
    ): List<Candidate> {
        if (limit <= 0) return emptyList()
        val custom = customIps.mapNotNull { safeLiteral(it, family) }.distinct().toMutableList()
        Collections.shuffle(custom, random)
        val chosenCustom = custom.take(minOf(CUSTOM_PER_ROUND, limit))
        val selected = LinkedHashMap<String, Candidate>()
        chosenCustom.forEach { selected[it] = Candidate(it, "我的 IP 名单") }
        val shuffledRanges = ranges.map { it.trim() }.filter { it.isNotBlank() }.distinct().toMutableList()
        Collections.shuffle(shuffledRanges, random)
        for (range in shuffledRanges) {
            if (selected.size >= limit) break
            val ip = randomFromPrefix(range, family, random) ?: continue
            selected.putIfAbsent(ip, Candidate(ip, "维护 IP 池"))
        }
        return selected.values.toMutableList().also { Collections.shuffle(it, random) }
    }

    private fun safeLiteral(value: String, family: String): String? = try {
        val normalized = IpSources.normalizeIp(value)
        val address = InetAddress.getByName(normalized)
        if (!IpSources.isPublicAddress(address)) null
        else if (family == "IPv4" && address !is Inet4Address) null
        else if (family == "IPv6" && address !is Inet6Address) null
        else IpSources.canonicalAddress(address)
    } catch (_: Exception) { null }

    private fun randomFromPrefix(value: String, family: String, random: Random): String? {
        val base = value.substringBefore('/').trim()
        return if (family == "IPv4") {
            val octets = base.split('.')
            if (octets.size != 4 || octets.any { it.toIntOrNull() !in 0..255 }) return null
            safeLiteral("${octets[0]}.${octets[1]}.${octets[2]}.${random.nextInt(256)}", family)
        } else {
            val address = try { InetAddress.getByName(base) as? Inet6Address } catch (_: Exception) { null } ?: return null
            val bytes = address.address.copyOf()
            // Observable reference behaviour: preserve the first three hextets
            // and randomise the remaining five, regardless of textual CIDR.
            for (index in 6 until bytes.size) bytes[index] = random.nextInt(256).toByte()
            val generated = try { InetAddress.getByAddress(bytes) } catch (_: Exception) { null } ?: return null
            safeLiteral(IpSources.canonicalAddress(generated), family)
        }
    }

    suspend fun scanFamily(
        family: String,
        data: MaintainedPoolData,
        customIps: Collection<String>,
        expectedMbps: Int,
        useTls: Boolean,
        networkInvalid: () -> Boolean = { false },
        routeValidator: (suspend (String) -> ProbeEngine.ArgoRouteResult)? = null,
        onStage: (Stage) -> Unit = {},
        log: (String) -> Unit = {}
    ): Winner {
        val ranges = if (family == "IPv6") data.ipv6Ranges else data.ipv4Ranges
        val threshold = expectedMbps.coerceIn(1, 2_000) * 128
        val random = Random(System.nanoTime() xor family.hashCode().toLong())
        var round = 0
        while (true) {
            coroutineContext.ensureActive()
            if (networkInvalid()) throw IllegalStateException("测试期间网络出口发生变化")
            round++
            val candidates = buildRoundCandidates(ranges, customIps, family, random)
            require(candidates.isNotEmpty()) { "$family 维护池没有可用候选" }
            log("$family 第 $round 轮：随机生成 ${candidates.size} 个候选")
            val rttRows = runRttRound(candidates, useTls, round, onStage)
            if (rttRows.isEmpty()) {
                log("$family 第 $round 轮 RTT 全部失败，自动换一批")
                continue
            }
            log("$family 保留最低延迟 ${rttRows.size} 个：" + rttRows.joinToString(" / ") { "${it.candidate.ip} ${it.latencyMs}ms" })
            val speedStage = "第 $round 轮 · 最低延迟 IP 逐个下载测速"
            onStage(Stage(speedStage, 0, rttRows.size, "目标 $expectedMbps Mbps"))
            rttRows.forEachIndexed { index, rtt ->
                coroutineContext.ensureActive()
                val speed = probeSpeed(rtt.candidate, data, useTls)
                onStage(Stage(speedStage, index + 1, rttRows.size, rtt.candidate.ip))
                if (speed.ok) {
                    log("${rtt.candidate.ip} 峰值 ${speed.peakKbps} kB/s (${speed.peakKbps / 128} Mbps) · TCP ${speed.tcpMs}ms · ${speed.colo.ifBlank { "未知 POP" }}")
                } else log("${rtt.candidate.ip} 下载测速失败：${speed.error}")
                if (!speed.ok || speed.peakKbps < threshold) return@forEachIndexed
                val route = if (routeValidator != null) {
                    onStage(Stage("V2rayNG 同口径节点复核", 0, 1, rtt.candidate.ip))
                    routeValidator(rtt.candidate.ip).also {
                        // Native Xray calls are blocking. A Stop/Back request
                        // can arrive while the call is in progress, so never
                        // accept its result before observing cancellation.
                        coroutineContext.ensureActive()
                        onStage(Stage("V2rayNG 同口径节点复核", 1, 1, rtt.candidate.ip))
                    }
                } else null
                if (route != null && !route.ok) {
                    log("${rtt.candidate.ip} 达到带宽，但完整节点在 Xray 中未连通，继续下一个")
                    return@forEachIndexed
                }
                if (networkInvalid()) throw IllegalStateException("测试期间网络出口发生变化")
                val location = data.locations[speed.colo] ?: speed.colo
                log("$family 已找到首个达标 IP：${rtt.candidate.ip}")
                return Winner(
                    ip = rtt.candidate.ip,
                    family = family,
                    source = "${rtt.candidate.source} · ${data.source}",
                    peakKbps = speed.peakKbps,
                    tcpMs = speed.tcpMs,
                    rttMs = rtt.latencyMs,
                    colo = speed.colo,
                    dataCenter = location,
                    bytesDownloaded = speed.bytesDownloaded,
                    round = round,
                    useTls = useTls,
                    speedHost = data.speedHost,
                    route = route
                )
            }
            log("$family 第 $round 轮没有 IP 达到 $expectedMbps Mbps，自动开始下一轮")
        }
    }

    private suspend fun runRttRound(
        candidates: List<Candidate>,
        useTls: Boolean,
        round: Int,
        onStage: (Stage) -> Unit
    ): List<RttCandidate> = coroutineScope {
        val done = AtomicInteger(0)
        val semaphore = Semaphore(minOf(RTT_CONCURRENCY, candidates.size))
        val name = "第 $round 轮 · 3 次 RTT / CF-RAY 验证"
        onStage(Stage(name, 0, candidates.size, "并发 ${minOf(RTT_CONCURRENCY, candidates.size)}"))
        candidates.map { candidate ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        val latency = probeRtt(candidate, useTls)
                        if (latency != null) RttCandidate(candidate, latency) else null
                    } finally {
                        val current = done.incrementAndGet()
                        onStage(Stage(name, current, candidates.size, candidate.ip))
                    }
                }
            }
        }.awaitAll().filterNotNull().sortedWith(compareBy<RttCandidate> { it.latencyMs }.thenBy { it.candidate.ip }).take(SPEED_SHORTLIST)
    }

    private suspend fun probeRtt(candidate: Candidate, useTls: Boolean): Int? = withContext(Dispatchers.IO) {
        val active = AtomicReference<Socket?>(null)
        val closeOnCancel = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion {
            try { active.getAndSet(null)?.close() } catch (_: Exception) {}
        }
        try {
            val values = ArrayList<Int>(RTT_ATTEMPTS)
            repeat(RTT_ATTEMPTS) {
                coroutineContext.ensureActive()
                values.add(rttOnce(candidate.ip, useTls, active))
            }
            values.sum() / values.size
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            closeOnCancel?.dispose()
            try { active.getAndSet(null)?.close() } catch (_: Exception) {}
        }
    }

    private fun rttOnce(ip: String, useTls: Boolean, active: AtomicReference<Socket?>): Int {
        val target = InetAddress.getByName(ip)
        val port = if (useTls) 443 else 80
        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        val raw = Socket(Proxy.NO_PROXY)
        active.set(raw)
        var stream: Socket = raw
        try {
            val started = System.nanoTime()
            raw.connect(InetSocketAddress(target, port), remainingMillis(deadlineNs))
            val connectedNs = System.nanoTime()
            require(ProbeEngine.addressesEqual(target, raw.inetAddress)) { "实际远端不一致" }
            if (useTls) {
                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val ssl = sslFactory.createSocket(raw, "cloudflare.com", port, true) as SSLSocket
                val parameters = ssl.sslParameters
                parameters.endpointIdentificationAlgorithm = "HTTPS"
                ssl.sslParameters = parameters
                ssl.soTimeout = remainingMillis(deadlineNs)
                ssl.startHandshake()
                stream = ssl
                active.set(ssl)
            }
            stream.soTimeout = remainingMillis(deadlineNs)
            val output = BufferedOutputStream(stream.getOutputStream())
            output.write(
                ("GET / HTTP/1.1\r\n" +
                    "Host: cloudflare.com\r\n" +
                    "User-Agent: Mozilla/5.0\r\n" +
                    "Accept-Encoding: identity\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
            )
            output.flush()
            stream.soTimeout = remainingMillis(deadlineNs)
            val headers = readHeaders(BufferedInputStream(stream.getInputStream()))
            require(headers.second.containsKey("cf-ray")) { "缺少 CF-RAY" }
            return maxOf(1, ((connectedNs - started) / 1_000_000L).toInt())
        } finally {
            active.set(null)
            try { stream.close() } catch (_: Exception) {}
            if (stream !== raw) try { raw.close() } catch (_: Exception) {}
        }
    }

    private fun remainingMillis(deadlineNs: Long): Int {
        val remaining = deadlineNs - System.nanoTime()
        if (remaining <= 0L) throw SocketTimeoutException("截止时间已到")
        return maxOf(1, TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    private fun readHeaders(input: BufferedInputStream): Pair<Int, Map<String, String>> {
        val bytes = ArrayList<Byte>()
        var matched = 0
        while (matched < 4) {
            val value = input.read()
            if (value < 0) throw EOFException("HTTP 响应提前结束")
            bytes.add(value.toByte())
            require(bytes.size <= 64 * 1024) { "HTTP 响应头过大" }
            val expected = byteArrayOf(13, 10, 13, 10)
            matched = if (value.toByte() == expected[matched]) matched + 1 else if (value == 13) 1 else 0
        }
        val text = bytes.toByteArray().toString(Charsets.ISO_8859_1)
        val lines = text.split("\r\n")
        val status = lines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: 0
        val headers = LinkedHashMap<String, String>()
        lines.drop(1).forEach { line ->
            val separator = line.indexOf(':')
            if (separator > 0) headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }
        return Pair(status, headers)
    }

    suspend fun probeSpeed(candidate: Candidate, data: MaintainedPoolData, useTls: Boolean): SpeedSample = withContext(Dispatchers.IO) {
        val targetAddress = InetAddress.getByName(candidate.ip)
        val connectStart = AtomicLong(0L)
        val connectEnd = AtomicLong(0L)
        val actualRemote = AtomicReference<InetAddress?>(null)
        val listener = object : EventListener() {
            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                connectStart.set(System.nanoTime())
                actualRemote.set(inetSocketAddress.address)
            }
            override fun secureConnectStart(call: Call) {
                // The reference result reports raw TCP dial latency, excluding
                // TLS. secureConnectStart is OkHttp's closest exact boundary.
                connectEnd.compareAndSet(0L, System.nanoTime())
            }
            override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
                connectEnd.compareAndSet(0L, System.nanoTime())
            }
        }
        val port = if (useTls) 443 else 80
        val scheme = if (useTls) "https" else "http"
        val authority = if ((useTls && port == 443) || (!useTls && port == 80)) data.speedHost else "${data.speedHost}:$port"
        val client = OkHttpClient.Builder()
            .dns(FixedDns.forTestHost(data.speedHost, candidate.ip))
            .proxy(Proxy.NO_PROXY)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
            .connectTimeout(SPEED_SECONDS.toLong(), TimeUnit.SECONDS)
            .readTimeout(SPEED_SECONDS.toLong(), TimeUnit.SECONDS)
            .callTimeout(SPEED_SECONDS.toLong(), TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .eventListener(listener)
            .build()
        val request = Request.Builder()
            .url("$scheme://$authority${data.speedPath}")
            .header("User-Agent", "RR-Edge-Hunter-Android/1.0")
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-store")
            .get().build()
        val call = client.newCall(request)
        val cancelHandle = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { call.cancel() }
        var totalBytes = 0L
        var maximumKbps = 0
        var colo = ""
        try {
            call.execute().use { response ->
                val remoteMatches = ProbeEngine.addressesEqual(targetAddress, actualRemote.get())
                colo = ProbeEngine.edgeColo(response.header("CF-RAY"))
                require(response.code in 200..299) { "HTTP ${response.code}" }
                require(remoteMatches) { "实际远端与候选 IP 不一致" }
                require(colo.isNotBlank()) { "缺少 CF-RAY" }
                require(!useTls || response.handshake != null) { "TLS 证书验证失败" }
                val source = response.body?.source() ?: error("测速响应为空")
                val buffer = Buffer()
                var windowBytes = 0L
                var windowStarted = System.nanoTime()
                try {
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = source.read(buffer, 32L * 1024)
                        if (count < 0L) break
                        totalBytes += count
                        windowBytes += count
                        buffer.clear()
                        val elapsed = (System.nanoTime() - windowStarted) / 1_000_000_000.0
                        if (elapsed >= 1.0) {
                            maximumKbps = maxOf(maximumKbps, (windowBytes / 1024.0 / elapsed).toInt())
                            windowBytes = 0L
                            windowStarted = System.nanoTime()
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    // Five-second call timeout is the expected end of the sample.
                } catch (e: java.io.InterruptedIOException) {
                    if (!coroutineContext[kotlinx.coroutines.Job]!!.isActive) throw e
                }
            }
            val tcpMs = if (connectStart.get() > 0L && connectEnd.get() > connectStart.get()) {
                maxOf(1, ((connectEnd.get() - connectStart.get()) / 1_000_000L).toInt())
            } else 0
            SpeedSample(
                ok = maximumKbps > 0,
                peakKbps = maximumKbps,
                tcpMs = tcpMs,
                colo = colo,
                bytesDownloaded = totalBytes,
                error = if (maximumKbps > 0) "" else "没有完整 1 秒下载窗口"
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IOException) {
            if (!coroutineContext[kotlinx.coroutines.Job]!!.isActive) {
                throw kotlinx.coroutines.CancellationException("已停止").apply { initCause(e) }
            }
            SpeedSample(false, maximumKbps, 0, colo, totalBytes, "${e.javaClass.simpleName}: ${e.message?.take(100)}")
        } catch (e: Exception) {
            SpeedSample(false, maximumKbps, 0, colo, totalBytes, "${e.javaClass.simpleName}: ${e.message?.take(100)}")
        } finally {
            cancelHandle?.dispose()
            call.cancel()
        }
    }
}

package com.xiaowu7z.cfipoptimizer.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.math.BigInteger
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Cloudflare 官方网段：在线获取 + 内置备用 + CIDR 判定（字节级，v4/v6 通用）。
 * 与 PS v3.0 逻辑一致：在线获取失败自动用内置备用。
 */
object CfRanges {
    private const val MAX_RANGE_RESPONSE_BYTES = 64 * 1024

    val FALLBACK_V4: List<String> = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
        "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
        "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
        "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22"
    )

    val FALLBACK_V6: List<String> = listOf(
        "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32",
        "2405:b500::/32", "2405:8100::/32", "2a06:98c0::/29", "2c0f:f248::/32"
    )

    @Volatile var rangesV4: List<String> = FALLBACK_V4
    @Volatile var rangesV6: List<String> = FALLBACK_V6
    @Volatile var v4FromOnline: Boolean = false
    @Volatile var v6FromOnline: Boolean = false

    /** 在线刷新官方网段（失败保持内置备用，取消会立即停止请求）。 */
    suspend fun refresh() {
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
        refreshWithFetcher { url -> fetchText(client, url) }
    }

    /** Separated for deterministic cancellation tests; production always supplies the strict client above. */
    internal suspend fun refreshWithFetcher(fetch: suspend (String) -> String?) {
        try {
            val v4 = fetch("https://www.cloudflare.com/ips-v4")
            val v6 = fetch("https://www.cloudflare.com/ips-v6")
            val parsed4 = parseRanges(v4, v4 = true)
            val parsed6 = parseRanges(v6, v4 = false)
            if (parsed4.isNotEmpty()) { rangesV4 = parsed4; v4FromOnline = true }
            if (parsed6.isNotEmpty()) { rangesV6 = parsed6; v6FromOnline = true }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 保持内置备用
        }
    }

    private suspend fun fetchText(client: OkHttpClient, url: String): String? {
        val call = client.newCall(Request.Builder().url(url).get().build())
        return awaitRangeResponse(call)
    }

    /**
     * Bridges an OkHttp call into structured concurrency. Cancelling the scan closes the
     * active socket/body read instead of leaving a dispatcher thread waiting for timeout.
     */
    internal suspend fun awaitRangeResponse(call: Call): String? = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        if (!continuation.isActive) {
            call.cancel()
            return@suspendCancellableCoroutine
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val text = try {
                    response.use { readRangeResponse(it) }
                } catch (_: Exception) {
                    null
                }
                if (continuation.isActive) continuation.resume(text)
            }
        })
    }

    private fun readRangeResponse(response: Response): String? {
        if (!response.isSuccessful) return null
        val body = response.body ?: return null
        if (body.contentLength() > MAX_RANGE_RESPONSE_BYTES) return null
        body.byteStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_RANGE_RESPONSE_BYTES) return null
                output.write(buffer, 0, count)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }

    private fun parseRanges(text: String?, v4: Boolean): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        val re = if (v4) Regex("""^\d+\.\d+\.\d+\.\d+/\d+$""")
                 else Regex("""^[0-9A-Fa-f:]+/\d+$""")
        val bits = if (v4) 32 else 128
        // Neither /0 nor an oversized supernet belongs in a candidate safety
        // allow-list.  Current published CF ranges are substantially narrower;
        // this guard prevents a malformed remote response from widening the
        // DNS-snapshot boundary to the whole Internet.
        val minimumPrefix = if (v4) 8 else 16
        return text.lineSequence().mapNotNull { line ->
            val cidr = line.trim()
            if (!re.matches(cidr)) return@mapNotNull null
            val addressText = cidr.substringBefore('/')
            val prefix = cidr.substringAfter('/').toIntOrNull() ?: return@mapNotNull null
            if (prefix !in minimumPrefix..bits) return@mapNotNull null
            val address = parseLiteralRangeAddress(addressText, v4) ?: return@mapNotNull null
            if ((v4 && address !is Inet4Address) || (!v4 && address !is Inet6Address)) return@mapNotNull null
            "$addressText/$prefix"
        }.distinct().toList()
    }

    /** Parses only an address literal; unlike a hostname parser, it never asks DNS. */
    private fun parseLiteralRangeAddress(value: String, v4: Boolean): InetAddress? {
        if (v4) {
            val parts = value.split('.')
            if (parts.size != 4) return null
            val bytes = ByteArray(4)
            for ((index, part) in parts.withIndex()) {
                if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
                val number = part.toIntOrNull() ?: return null
                if (number !in 0..255) return null
                bytes[index] = number.toByte()
            }
            return InetAddress.getByAddress(bytes)
        }
        if (!value.contains(':') || value.length > 45 || !value.all { it in "0123456789abcdefABCDEF:." }) return null
        return try {
            InetAddress.getByName(value).takeIf { it is Inet6Address }
        } catch (_: Exception) {
            null
        }
    }

    /** CIDR 判定：address 是否在 cidr 网段内（字节级）。 */
    fun inCidr(address: InetAddress, cidr: String): Boolean {
        try {
            val parts = cidr.split("/")
            val net = InetAddress.getByName(parts[0])
            val prefix = parts[1].toInt()
            val a = address.address          // 4 或 16 字节
            val n = net.address
            if (a.size != n.size) return false
            val fullBytes = prefix / 8
            val remBits = prefix % 8
            for (i in 0 until fullBytes) {
                if (a[i] != n[i]) return false
            }
            if (remBits > 0 && fullBytes < a.size) {
                val mask = (0xFF shl (8 - remBits)) and 0xFF
                if ((a[fullBytes].toInt() and mask) != (n[fullBytes].toInt() and mask)) return false
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /** 判断地址是否属于 CF 官方网段。 */
    fun isCloudflare(addr: InetAddress): Boolean {
        val ranges = when (addr) {
            is Inet4Address -> rangesV4
            is Inet6Address -> rangesV6
            else -> emptyList()
        }
        return ranges.any { inCidr(addr, it) }
    }

    /**
     * 从每个 Cloudflare 官方网段中按本轮 seed 有界轮转抽取少量地址。
     *
     * 这里不会展开整个 CIDR，也不会随机扫描互联网。调用方仍须使用公开测速
     * 主机或获授权 Argo 域名的 SNI/Host 与系统证书校验逐个确认候选。
     */
    fun sampleOfficial(
        family: String,
        perRange: Int = 2,
        limit: Int = 64,
        seed: Long = 0L
    ): List<String> {
        if (perRange <= 0 || limit <= 0) return emptyList()
        val ranges = when (family) {
            "IPv4" -> rangesV4
            "IPv6" -> rangesV6
            else -> return emptyList()
        }
        val result = LinkedHashSet<String>()
        for ((rangeIndex, cidr) in ranges.withIndex()) {
            val parts = cidr.split('/')
            if (parts.size != 2) continue
            val networkAddress = try { InetAddress.getByName(parts[0]) } catch (_: Exception) { continue }
            val bits = when (networkAddress) {
                is Inet4Address -> 32
                is Inet6Address -> 128
                else -> continue
            }
            if ((family == "IPv4" && bits != 32) || (family == "IPv6" && bits != 128)) continue
            val prefix = parts[1].toIntOrNull() ?: continue
            if (prefix !in 0..bits) continue
            val span = BigInteger.ONE.shiftLeft(bits - prefix)
            val skipEdges = bits == 32 && bits - prefix >= 2
            val skipIpv6Network = bits == 128 && span > BigInteger.ONE
            val first = if (skipEdges || skipIpv6Network) BigInteger.ONE else BigInteger.ZERO
            val last = span.subtract(if (skipEdges) BigInteger.valueOf(2L) else BigInteger.ONE)
            if (last < first) continue
            val usable = last.subtract(first).add(BigInteger.ONE)
            val count = minOf(perRange, if (usable > BigInteger.valueOf(Int.MAX_VALUE.toLong())) perRange else usable.toInt())
            val network = BigInteger(1, networkAddress.address)
            repeat(count) { index ->
                // Partitioned, seeded selection rotates bounded official samples
                // between runs without expanding a CIDR or trusting an external
                // pool.  Broad IPv6 ranges deliberately stay inside the first
                // 65,536 addresses; arbitrary deep /32 addresses are unlikely to
                // be useful service endpoints.
                val selectionSpan = if (bits == 128) {
                    usable.min(BigInteger.valueOf(65_536L))
                } else usable
                val partitionStart = selectionSpan.multiply(BigInteger.valueOf(index.toLong()))
                    .divide(BigInteger.valueOf(count.toLong()))
                val partitionEnd = selectionSpan.multiply(BigInteger.valueOf((index + 1L)))
                    .divide(BigInteger.valueOf(count.toLong()))
                val partitionSize = partitionEnd.subtract(partitionStart).max(BigInteger.ONE)
                val mixed = mixSeed(seed, rangeIndex, index)
                val jitter = BigInteger.valueOf(mixed and Long.MAX_VALUE).mod(partitionSize)
                val offset = partitionStart.add(jitter).coerceAtMost(usable.subtract(BigInteger.ONE))
                val value = network.add(first).add(offset)
                val bytes = ByteArray(bits / 8)
                val raw = value.toByteArray()
                val copyLength = minOf(raw.size, bytes.size)
                System.arraycopy(raw, raw.size - copyLength, bytes, bytes.size - copyLength, copyLength)
                val address = try { InetAddress.getByAddress(bytes) } catch (_: Exception) { null } ?: return@repeat
                if (isCloudflare(address)) result.add(address.hostAddress.substringBefore('%').lowercase())
            }
            if (result.size >= limit) break
        }
        return result.take(limit)
    }

    private fun mixSeed(seed: Long, rangeIndex: Int, sampleIndex: Int): Long {
        var value = seed xor (rangeIndex.toLong() * -7046029254386353131L) xor
            (sampleIndex.toLong() * -4658895280553007687L)
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        return value xor (value ushr 31)
    }
}

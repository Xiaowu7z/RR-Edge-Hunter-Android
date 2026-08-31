package com.xiaowu7z.cfipoptimizer.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

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

    /** 在线刷新官方网段（失败保持内置备用）。 */
    fun refresh() {
        try {
            val client = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .build()
            val v4 = fetchText(client, "https://www.cloudflare.com/ips-v4")
            val v6 = fetchText(client, "https://www.cloudflare.com/ips-v6")
            val parsed4 = parseRanges(v4, v4 = true)
            val parsed6 = parseRanges(v6, v4 = false)
            if (parsed4.isNotEmpty()) { rangesV4 = parsed4; v4FromOnline = true }
            if (parsed6.isNotEmpty()) { rangesV6 = parsed6; v6FromOnline = true }
        } catch (e: Exception) {
            // 保持内置备用
        }
    }

    private fun fetchText(client: OkHttpClient, url: String): String? {
        return try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val contentLength = response.body?.contentLength() ?: -1L
                if (contentLength > MAX_RANGE_RESPONSE_BYTES) return null
                val body = response.body ?: return null
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
                    output.toString(Charsets.UTF_8.name())
                }
            }
        } catch (e: Exception) { null }
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
}

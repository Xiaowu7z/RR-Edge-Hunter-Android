package com.xiaowu7z.cfipoptimizer.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.File
import java.net.IDN
import java.net.InetAddress
import java.net.Proxy
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class MaintainedPoolData(
    val ipv4Ranges: List<String>,
    val ipv6Ranges: List<String>,
    val speedHost: String,
    val speedPath: String,
    val locations: Map<String, String>,
    val source: String
)

/**
 * Downloads the public subnet feed and dynamic speed target used by the
 * reference application. Data is kept locally for six hours and a stale cache
 * remains usable when the maintainer is temporarily unreachable.
 */
object MaintainedPool {
    const val IPV4_URL = "https://www.baipiao.eu.org/cloudflare/ips-v4"
    const val IPV6_URL = "https://www.baipiao.eu.org/cloudflare/ips-v6"
    const val SPEED_URL = "https://www.baipiao.eu.org/cloudflare/url"
    const val LOCATIONS_URL = "https://www.baipiao.eu.org/cloudflare/locations"

    private const val CACHE_MAX_AGE_MS = 6L * 60 * 60 * 1_000
    private const val MAX_POOL_BYTES = 4L * 1024 * 1024
    private const val MAX_SMALL_BYTES = 64L * 1024
    private const val FALLBACK_SPEED = "speed.cloudflare.com/__down?bytes=250000000"

    private val fallbackV4 = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
        "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
        "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
        "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22"
    )
    private val fallbackV6 = listOf(
        "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32",
        "2405:8100::/32", "2a06:98c0::/29", "2c0f:f248::/32"
    )

    private fun cacheDir(root: File) = File(root, "reference-pool-v1")

    suspend fun load(
        root: File,
        forceRefresh: Boolean = false,
        log: (String) -> Unit = {}
    ): MaintainedPoolData = withContext(Dispatchers.IO) {
        val dir = cacheDir(root)
        val cached = readCache(dir)
        val fresh = cached != null && System.currentTimeMillis() - File(dir, "updated-at").lastModified() <= CACHE_MAX_AGE_MS
        if (fresh && !forceRefresh) return@withContext cached
        try {
            coroutineContext.ensureActive()
            val speedText = fetch(SPEED_URL, MAX_SMALL_BYTES)
            val speed = parseSpeedTarget(speedText)
            val v4 = parseRanges(fetch(IPV4_URL, MAX_POOL_BYTES), "IPv4")
            val v6 = parseRanges(fetch(IPV6_URL, MAX_POOL_BYTES), "IPv6")
            val locationsText = fetch(LOCATIONS_URL, MAX_POOL_BYTES)
            val locations = parseLocations(locationsText)
            require(v4.isNotEmpty() && v6.isNotEmpty()) { "在线维护 IP 池为空" }
            writeCache(dir, v4, v6, speedText.trim(), locationsText)
            log("维护数据已更新：IPv4 ${v4.size} 段 / IPv6 ${v6.size} 段")
            MaintainedPoolData(v4, v6, speed.first, speed.second, locations, "baipiao.eu.org 维护池")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cached != null) {
                log("在线维护数据不可用，使用上次缓存：${e.javaClass.simpleName}")
                cached.copy(source = "维护池离线缓存")
            } else {
                val speed = parseSpeedTarget(FALLBACK_SPEED)
                log("在线维护数据不可用，使用 Cloudflare 官方备用网段：${e.javaClass.simpleName}")
                MaintainedPoolData(fallbackV4, fallbackV6, speed.first, speed.second, emptyMap(), "Cloudflare 官方备用池")
            }
        }
    }

    private fun fetch(url: String, maxBytes: Long): String {
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val call = client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", "RR-Edge-Hunter-Android/1.0")
                .header("Accept", "text/plain, application/json")
                .get().build()
        )
        call.execute().use { response ->
            require(response.isSuccessful) { "维护数据 HTTP ${response.code}" }
            require(response.request.url.isHttps && response.request.url.host == "www.baipiao.eu.org") {
                "维护数据发生了不受信任的跳转"
            }
            val declared = response.body?.contentLength() ?: -1L
            require(declared < 0L || declared <= maxBytes) { "维护数据超过大小上限" }
            val source = response.body?.source() ?: error("维护数据响应为空")
            val output = Buffer()
            var total = 0L
            while (true) {
                val count = source.read(output, minOf(64L * 1024, maxBytes + 1 - total))
                if (count < 0L) break
                total += count
                require(total <= maxBytes) { "维护数据超过大小上限" }
            }
            return output.readString(Charsets.UTF_8).removePrefix("\uFEFF")
        }
    }

    fun parseSpeedTarget(value: String): Pair<String, String> {
        val raw = value.trim()
            .removePrefix("https://")
            .removePrefix("http://")
        require(raw.isNotBlank() && !raw.startsWith("/")) { "测速地址为空" }
        val slash = raw.indexOf('/')
        val hostRaw = if (slash < 0) raw else raw.substring(0, slash)
        val path = if (slash < 0) "/" else raw.substring(slash)
        require(!hostRaw.contains('@') && !hostRaw.contains(':') && path.startsWith('/') && path.length <= 2_048) {
            "测速地址格式无效"
        }
        val host = IDN.toASCII(hostRaw.trim().trimEnd('.')).lowercase()
        require(host.isNotBlank() && host.length <= 253 && host.contains('.')) { "测速域名无效" }
        require(!ProbeEngine.isIpLiteral(host) && path.all { it.code in 0x21..0x7e }) { "测速地址格式无效" }
        return Pair(host, path)
    }

    fun parseRanges(text: String, family: String): List<String> {
        val expectedBytes = if (family == "IPv6") 16 else 4
        val seen = LinkedHashSet<String>()
        text.lineSequence().forEach { raw ->
            val value = raw.substringBefore('#').trim()
            if (value.isBlank()) return@forEach
            val base = value.substringBefore('/')
            val prefix = value.substringAfter('/', if (expectedBytes == 16) "128" else "32").toIntOrNull()
                ?: return@forEach
            if (prefix !in 0..expectedBytes * 8) return@forEach
            val address = try { InetAddress.getByName(base) } catch (_: Exception) { null } ?: return@forEach
            if (address.address.size != expectedBytes) return@forEach
            val networkBytes = address.address.copyOf()
            val fullBytes = prefix / 8
            val remainingBits = prefix % 8
            if (remainingBits > 0 && fullBytes < networkBytes.size) {
                val mask = (0xff shl (8 - remainingBits)) and 0xff
                networkBytes[fullBytes] = (networkBytes[fullBytes].toInt() and mask).toByte()
            }
            val clearFrom = fullBytes + if (remainingBits > 0) 1 else 0
            for (index in clearFrom until networkBytes.size) networkBytes[index] = 0
            val networkAddress = try { InetAddress.getByAddress(networkBytes) } catch (_: Exception) { null } ?: return@forEach
            if (!com.xiaowu7z.cfipoptimizer.IpSources.isPublicAddress(networkAddress)) return@forEach
            seen.add("${com.xiaowu7z.cfipoptimizer.IpSources.canonicalAddress(networkAddress)}/$prefix")
        }
        return seen.toList()
    }

    /** Small, bounded parser sufficient for the public locations array. */
    fun parseLocations(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val objectRegex = Regex("""\{[^{}]{0,4096}}""")
        val iataRegex = Regex(""""iata"\s*:\s*"([A-Za-z]{3})"""")
        val cityRegex = Regex(""""city"\s*:\s*"([^"\\]{1,200})"""")
        objectRegex.findAll(text).take(2_000).forEach { objectMatch ->
            val block = objectMatch.value
            val code = iataRegex.find(block)?.groupValues?.get(1)?.uppercase() ?: return@forEach
            val city = cityRegex.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            result[code] = city.ifBlank { code }
        }
        return result
    }

    private fun readCache(dir: File): MaintainedPoolData? = try {
        val v4 = parseRanges(File(dir, "ips-v4.txt").readText(), "IPv4")
        val v6 = parseRanges(File(dir, "ips-v6.txt").readText(), "IPv6")
        val speed = parseSpeedTarget(File(dir, "url.txt").readText())
        val locations = parseLocations(File(dir, "locations.json").readText())
        if (v4.isEmpty() || v6.isEmpty()) null
        else MaintainedPoolData(v4, v6, speed.first, speed.second, locations, "维护池缓存")
    } catch (_: Exception) { null }

    private fun writeCache(
        dir: File,
        v4: List<String>,
        v6: List<String>,
        speed: String,
        locations: String
    ) {
        dir.mkdirs()
        replaceFile(File(dir, "ips-v4.txt"), v4.joinToString("\n"))
        replaceFile(File(dir, "ips-v6.txt"), v6.joinToString("\n"))
        replaceFile(File(dir, "url.txt"), speed)
        replaceFile(File(dir, "locations.json"), locations)
        File(dir, "updated-at").apply { writeText(System.currentTimeMillis().toString()); setLastModified(System.currentTimeMillis()) }
    }

    private fun replaceFile(target: File, value: String) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(value)
        try {
            Files.move(
                temporary.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

package com.xiaowu7z.cfipoptimizer.engine

import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

/**
 * A per-run, immutable DNS snapshot for the Argo hostname.
 *
 * The snapshot proves that the hostname is currently proxied by Cloudflare and
 * provides trusted seed addresses. Argo 优选模式 may additionally evaluate a
 * bounded set of addresses from Cloudflare's published ranges while preserving
 * this hostname as TLS SNI and HTTP Host.
 */
data class AuthorizedHostSnapshot(
    val host: String,
    val ipv4: List<String>,
    val ipv6: List<String>,
    val resolvedAtMillis: Long = System.currentTimeMillis()
) {
    val all: List<String> get() = ipv4 + ipv6

    fun forFamily(family: String): List<String> = when (family) {
        "IPv4" -> ipv4
        "IPv6" -> ipv6
        else -> all
    }
}

data class AuthorizedCandidateSelection(
    val candidates: List<String>,
    val importedCount: Int,
    val intersectionCount: Int,
    val ignoredOutsideCurrentDns: Int
)

object AuthorizedHost {
    private val label = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
    private val dottedNumericV4 = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")

    /** Normalizes a DNS hostname, rejecting URL/IP/port input. */
    fun normalizeHost(value: String): String {
        var raw = value.trim().trimEnd('.')
        if (raw.isEmpty() || raw.length > 253 || raw.contains('/') || raw.contains('@') || raw.contains(':')) {
            throw IllegalArgumentException("测试主机必须是域名，不能是 IP、URL 或端口")
        }
        raw = try {
            IDN.toASCII(raw, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        } catch (_: Exception) {
            throw IllegalArgumentException("测试主机域名格式无效")
        }
        // Four numeric labels are an IPv4-looking target, even if an invalid
        // octet spelling would not parse as a conventional address.  A test
        // host must be an actual domain name, never an address literal.
        if (dottedNumericV4.matches(raw)) {
            throw IllegalArgumentException("测试主机必须是域名，不能是 IP、URL 或端口")
        }
        if (!raw.contains('.') || raw.split('.').any { !label.matches(it) }) {
            throw IllegalArgumentException("测试主机域名格式无效")
        }
        return raw
    }

    /** Optional WebSocket path used only for an Argo compatibility handshake. */
    fun normalizeWsPath(value: String): String {
        val raw = value.trim()
        if (raw.isEmpty()) return ""
        if (raw.length > 1024 || !raw.startsWith('/') || raw.startsWith("//") ||
            raw.any { it.isWhitespace() || it.code < 0x20 } || raw.contains('#') || raw.contains('\\')) {
            throw IllegalArgumentException("WS Path 必须以 / 开头，可留空并保持原节点配置")
        }
        fun asciiHex(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
        raw.forEachIndexed { index, char ->
            if (char == '%' && (index + 2 >= raw.length ||
                    !asciiHex(raw[index + 1]) || !asciiHex(raw[index + 2]))) {
                throw IllegalArgumentException("WS Path 含有无效的 % 转义")
            }
        }
        return raw
    }

    /** Resolves actual current Cloudflare addresses for the selected host. */
    fun snapshot(hostInput: String, log: (String) -> Unit = {}): AuthorizedHostSnapshot {
        val host = normalizeHost(hostInput)
        val addresses = try {
            InetAddress.getAllByName(host).toList()
        } catch (e: Exception) {
            throw IllegalArgumentException("测试主机 DNS 解析失败：${e.message?.take(100) ?: e.javaClass.simpleName}")
        }
        val v4 = LinkedHashSet<String>()
        val v6 = LinkedHashSet<String>()
        var nonCloudflare = 0
        for (address in addresses) {
            if (!CfRanges.isCloudflare(address)) {
                nonCloudflare++
                continue
            }
            when (address) {
                is Inet4Address -> v4.add(address.hostAddress)
                is Inet6Address -> v6.add(address.hostAddress)
            }
        }
        if (v4.isEmpty() && v6.isEmpty()) {
            throw IllegalArgumentException("测试主机当前 DNS 没有 Cloudflare IP；为避免任意 IP 探测，本轮已拒绝")
        }
        log("授权 DNS 快照：$host → IPv4 ${v4.size} / IPv6 ${v6.size}${if (nonCloudflare > 0) "（忽略非 Cloudflare $nonCloudflare）" else ""}")
        return AuthorizedHostSnapshot(host, v4.toList(), v6.toList())
    }

    /** Legacy/current-DNS diagnostic helper. Argo 优选 uses [CandidatePool]. */
    fun intersectImported(
        snapshot: AuthorizedHostSnapshot,
        imported: Collection<String>,
        family: String
    ): AuthorizedCandidateSelection {
        // Do not compare IPv6 presentation strings: Android/JDK may choose a
        // compressed or expanded spelling for the same 16 bytes.
        fun key(address: InetAddress): String = address.address.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val allowed = LinkedHashMap<String, String>()
        for (value in snapshot.forFamily(family)) {
            val address = try { InetAddress.getByName(value) } catch (_: Exception) { null } ?: continue
            allowed[key(address)] = value
        }
        val normalizedImported = LinkedHashSet<String>()
        for (raw in imported) {
            val addr = try { InetAddress.getByName(raw) } catch (_: Exception) { null } ?: continue
            val compatible = when (family) {
                "IPv4" -> addr is Inet4Address
                "IPv6" -> addr is Inet6Address
                else -> true
            }
            if (compatible) normalizedImported.add(key(addr))
        }
        val matches = allowed.filterKeys { it in normalizedImported }.values.toList()
        return AuthorizedCandidateSelection(
            candidates = matches,
            importedCount = normalizedImported.size,
            intersectionCount = matches.size,
            ignoredOutsideCurrentDns = (normalizedImported - allowed.keys).size
        )
    }
}

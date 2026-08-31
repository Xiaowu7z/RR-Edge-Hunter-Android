package com.xiaowu7z.cfipoptimizer

import java.math.BigInteger
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.util.Base64
import java.util.Locale

/** A source was malformed, unsafe, or did not contain usable public IP candidates. */
class IpSourceException(message: String) : IllegalArgumentException(message)

/**
 * Normalized candidates from a pasted list, local file, or subscription.
 *
 * CIDRs are intentionally represented by their bounded samples only.  This keeps a
 * `/0` or a wide IPv6 prefix from turning an import action into an unbounded scan.
 */
data class IpParseResult(
    val ips: List<String>,
    val sourceFormat: String,
    val ignored: Int = 0,
    val cidrCount: Int = 0,
    val sampledCidrs: Int = 0,
    val warnings: List<String> = emptyList()
) {
    val ipv4Count: Int get() = ips.count { !it.contains(':') }
    val ipv6Count: Int get() = ips.size - ipv4Count
}

/** Content-based parser for public IPv4/IPv6 candidate sources. */
object IpSources {
    const val MAX_SOURCE_BYTES = 1_048_576
    const val MAX_IPS = 2_000
    const val MAX_CIDR_SAMPLES = 96
    const val MAX_SOURCE_VALUES = 10_000
    const val MAX_JSON_DEPTH = 64
    const val MAX_JSON_NODES = 20_000
    const val PROBE_PORT = 443

    private val jsonIpKeys = setOf(
        "ip", "ips", "ip_address", "ipaddress", "address", "addresses",
        "ipv4", "ipv6", "server", "servers", "host", "hosts", "endpoint", "endpoints"
    )
    private val jsonListKeys = setOf(
        "ips", "addresses", "items", "data", "results", "entries", "servers", "nodes", "hosts"
    )
    private val csvIpHeaders = setOf(
        "ip", "ips", "ip_address", "ipaddress", "address", "addresses",
        "ipv4", "ipv6", "server", "servers", "host", "hosts", "endpoint",
        "ip地址", "地址", "服务器"
    )

    /** Normalize one literal public IP or IP:443 value. CIDR belongs in [parse]. */
    fun normalizeIp(value: String): String {
        val raw = extractAddressToken(value)
        if (raw.isEmpty()) throw IpSourceException("IP 为空")
        if (raw.contains('/')) throw IpSourceException("CIDR 需要按列表解析")
        val address = parseLiteralAddress(raw) ?: throw IpSourceException("不是有效 IP 地址")
        if (!isPublicAddress(address)) throw IpSourceException("仅允许公网 IP 地址")
        return canonicalAddress(address)
    }

    /** Parse arbitrary source bytes after a strict size and text-encoding check. */
    fun parseBytes(payload: ByteArray, filename: String = ""): IpParseResult =
        parse(decodeBytes(payload), filename)

    fun decodeBytes(payload: ByteArray): String {
        if (payload.size > MAX_SOURCE_BYTES) throw IpSourceException("IP 来源文件不能超过 1 MiB")
        if (payload.any { it == 0.toByte() }) {
            throw IpSourceException("检测到二进制内容，只支持文本 IP 来源")
        }
        val charsets = listOf(Charsets.UTF_8, Charset.forName("GB18030"))
        for (charset in charsets) {
            try {
                val decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                return decoder.decode(ByteBuffer.wrap(payload)).toString().removePrefix("\uFEFF")
            } catch (_: CharacterCodingException) {
                // Try the next supported text encoding.
            }
        }
        throw IpSourceException("文件编码无法识别，请使用 UTF-8 或 GB18030 文本")
    }

    /**
     * Detect TXT/CSV/TSV/JSON/Base64 by content, never solely by a file extension.
     * Invalid, private and reserved rows are skipped but reported in [IpParseResult].
     */
    fun parse(text: String, filename: String = "", allowBase64: Boolean = true): IpParseResult {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_SOURCE_BYTES) {
            throw IpSourceException("IP 来源文件不能超过 1 MiB")
        }
        if (text.indexOf('\u0000') >= 0) {
            throw IpSourceException("检测到二进制内容，只支持文本 IP 来源")
        }
        val stripped = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (stripped.isEmpty()) throw IpSourceException("IP 来源内容为空")

        var sourceFormat = "TXT"
        val values: List<String>
        // A bracketed IPv6 endpoint (`[2606:...]:443`) is TXT, not a JSON array.
        val jsonArrayStart = stripped.startsWith('[') && stripped.drop(1).trimStart().firstOrNull() in setOf('"', '{', '[', ']')
        if (stripped.startsWith('{') || jsonArrayStart) {
            val parsed = try {
                IpJson.parse(stripped)
            } catch (_: Exception) {
                throw IpSourceException("JSON 格式无效")
            }
            values = jsonValues(parsed)
            sourceFormat = "JSON"
            if (values.isEmpty()) {
                throw IpSourceException("JSON 中未找到 ip/address/ips 等受支持字段")
            }
        } else {
            val decoded = if (allowBase64) tryBase64(stripped) else null
            if (decoded != null) {
                val inner = parse(decoded, filename, allowBase64 = false)
                return inner.copy(sourceFormat = "Base64 + ${inner.sourceFormat}")
            }
            val csv = csvValues(stripped)
            if (csv != null) {
                values = csv.first
                sourceFormat = csv.second
            } else {
                values = plainValues(stripped)
                val suffix = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (suffix in setOf("csv", "tsv", "json")) {
                    sourceFormat = "TXT（内容与 .$suffix 扩展名不一致）"
                }
            }
        }
        if (values.size > MAX_SOURCE_VALUES) {
            throw IpSourceException("IP 来源条目不能超过 $MAX_SOURCE_VALUES 个")
        }

        val ips = ArrayList<String>()
        val seen = LinkedHashSet<String>()
        var ignored = 0
        var cidrCount = 0
        var sampledCidrs = 0
        var ignoredUnsupportedPorts = 0
        for (value in values) {
            val raw = try {
                extractAddressToken(value)
            } catch (e: IpSourceException) {
                ignored++
                if (e.message?.contains("HTTPS 443") == true) ignoredUnsupportedPorts++
                continue
            }
            if (raw.contains('/')) cidrCount++
            val candidates = try {
                expandValue(raw)
            } catch (_: IpSourceException) {
                ignored++
                continue
            }
            if (candidates.sampled) sampledCidrs++
            for (candidate in candidates.ips) {
                if (!seen.add(candidate)) continue
                ips.add(candidate)
                if (ips.size > MAX_IPS) {
                    throw IpSourceException("单次最多载入 $MAX_IPS 个 IP；CIDR 会被安全抽样")
                }
            }
        }
        if (ips.isEmpty()) {
            if (ignoredUnsupportedPorts > 0) {
                throw IpSourceException("没有可用候选；当前测速固定使用 HTTPS 443 端口")
            }
            throw IpSourceException("没有识别到有效公网 IP；支持 TXT、CSV、TSV、JSON、Base64、IP:端口和 CIDR")
        }
        val warnings = buildList {
            if (ignored > 0) add("已忽略 $ignored 个无效、私网或保留字段")
            if (ignoredUnsupportedPorts > 0) {
                add("已忽略 $ignoredUnsupportedPorts 个非 443 端口；当前测速固定使用 HTTPS 443")
            }
            if (sampledCidrs > 0) add("$sampledCidrs 个 CIDR 已按每段最多 $MAX_CIDR_SAMPLES 个地址安全抽样")
        }
        return IpParseResult(ips, sourceFormat, ignored, cidrCount, sampledCidrs, warnings)
    }

    /** Useful for callers that need to keep IPv4/IPv6 run modes separate. */
    fun familyOf(value: String): String = if (normalizeIp(value).contains(':')) "IPv6" else "IPv4"

    /** Shared safe-public-address check for subscriptions and imported candidates. */
    fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address.map { it.toInt() and 0xff }
        return when (address) {
            is Inet4Address -> isPublicIpv4(bytes)
            is Inet6Address -> isPublicIpv6(bytes)
            else -> false
        }
    }

    /**
     * Literal-only parser: it never delegates malformed input to DNS.  It is internal
     * so the subscription layer can distinguish literal hosts from DNS hostnames.
     */
    internal fun parseLiteralAddress(value: String): InetAddress? =
        parseIpv4(value) ?: parseIpv6(value)

    internal fun canonicalAddress(address: InetAddress): String = when (address) {
        is Inet4Address -> address.address.joinToString(".") { (it.toInt() and 0xff).toString() }
        is Inet6Address -> canonicalIpv6(address.address)
        else -> address.hostAddress.lowercase(Locale.ROOT)
    }

    private data class ExpandedValue(val ips: List<String>, val sampled: Boolean)

    private fun expandValue(raw: String): ExpandedValue {
        if (raw.isBlank()) throw IpSourceException("IP 为空")
        if (!raw.contains('/')) return ExpandedValue(listOf(normalizeIp(raw)), false)
        val cidr = parseCidr(raw)
        if (!isPublicAddress(cidr.networkAddress)) throw IpSourceException("仅允许公网 CIDR")
        val values = sampleCidr(cidr).mapNotNull { address ->
            address.takeIf(::isPublicAddress)?.let(::canonicalAddress)
        }
        if (values.isEmpty()) throw IpSourceException("CIDR 未产生可用公网 IP")
        return ExpandedValue(values, cidr.sampled)
    }

    private data class ParsedCidr(
        val network: BigInteger,
        val bits: Int,
        val prefix: Int,
        val networkAddress: InetAddress,
        val sampled: Boolean
    )

    private fun parseCidr(raw: String): ParsedCidr {
        if (raw.count { it == '/' } != 1) throw IpSourceException("CIDR 格式无效")
        val ipText = raw.substringBefore('/').trim()
        val prefixText = raw.substringAfter('/').trim()
        if (ipText.isEmpty() || prefixText.isEmpty() || !prefixText.all { it.isDigit() }) {
            throw IpSourceException("CIDR 格式无效")
        }
        val address = parseLiteralAddress(ipText) ?: throw IpSourceException("CIDR 格式无效")
        val bits = if (address is Inet4Address) 32 else 128
        val prefix = prefixText.toIntOrNull() ?: throw IpSourceException("CIDR 前缀无效")
        if (prefix !in 0..bits) throw IpSourceException("CIDR 前缀无效")
        val allBits = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
        val mask = if (prefix == 0) BigInteger.ZERO else allBits.shiftRight(bits - prefix).shiftLeft(bits - prefix)
        val network = BigInteger(1, address.address).and(mask)
        val networkAddress = addressFromInteger(network, bits)
        val span = BigInteger.ONE.shiftLeft(bits - prefix)
        val usableSpan = if (address is Inet4Address && prefix <= 30) span.subtract(BigInteger.valueOf(2)) else span
        if (usableSpan <= BigInteger.ZERO) throw IpSourceException("CIDR 没有可用地址")
        return ParsedCidr(
            network = network,
            bits = bits,
            prefix = prefix,
            networkAddress = networkAddress,
            sampled = usableSpan > BigInteger.valueOf(MAX_CIDR_SAMPLES.toLong())
        )
    }

    private fun sampleCidr(cidr: ParsedCidr): List<InetAddress> {
        val fullSpan = BigInteger.ONE.shiftLeft(cidr.bits - cidr.prefix)
        val skipNetworkAndBroadcast = cidr.bits == 32 && cidr.prefix <= 30
        val start = if (skipNetworkAndBroadcast) cidr.network.add(BigInteger.ONE) else cidr.network
        val span = if (skipNetworkAndBroadcast) fullSpan.subtract(BigInteger.valueOf(2)) else fullSpan
        val count = if (span <= BigInteger.valueOf(MAX_CIDR_SAMPLES.toLong())) span.toInt() else MAX_CIDR_SAMPLES
        if (count <= 0) return emptyList()
        val offsets = ArrayList<BigInteger>(count)
        if (count == 1) {
            offsets.add(BigInteger.ZERO)
        } else if (span <= BigInteger.valueOf(MAX_CIDR_SAMPLES.toLong())) {
            repeat(count) { offsets.add(BigInteger.valueOf(it.toLong())) }
        } else {
            val maxOffset = span.subtract(BigInteger.ONE)
            val denominator = BigInteger.valueOf((count - 1).toLong())
            repeat(count) { index ->
                offsets.add(maxOffset.multiply(BigInteger.valueOf(index.toLong())).divide(denominator))
            }
        }
        return offsets.map { offset -> addressFromInteger(start.add(offset), cidr.bits) }
    }

    private fun addressFromInteger(value: BigInteger, bits: Int): InetAddress {
        val bytes = ByteArray(bits / 8)
        val raw = value.toByteArray()
        val copyStart = maxOf(0, raw.size - bytes.size)
        val copyLength = minOf(raw.size, bytes.size)
        System.arraycopy(raw, copyStart, bytes, bytes.size - copyLength, copyLength)
        return InetAddress.getByAddress(bytes)
    }

    private fun extractAddressToken(value: String): String {
        var raw = value.trim()
            .trim('"', '\'', '`', '(', ')', '{', '}', '<', '>')
        if (raw.isEmpty()) return ""
        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            throw IpSourceException("候选来源只接受 IP、IP:443 或 CIDR，不接受 URL")
        }
        if (raw.startsWith("[")) {
            val close = raw.indexOf(']')
            if (close <= 1) return raw
            val host = raw.substring(1, close)
            val rest = raw.substring(close + 1)
            if (rest.isEmpty()) return host
            if (rest.startsWith(':') && validPort(rest.drop(1))) {
                if (rest.drop(1).toInt() != PROBE_PORT) {
                    throw IpSourceException("当前测速仅支持 HTTPS 443 端口")
                }
                return host
            }
            return raw
        }
        if (raw.count { it == ':' } == 1) {
            val host = raw.substringBeforeLast(':')
            val port = raw.substringAfterLast(':')
            if (validPort(port) && parseIpv4(host) != null) {
                if (port.toInt() != PROBE_PORT) {
                    throw IpSourceException("当前测速仅支持 HTTPS 443 端口")
                }
                return host
            }
        }
        return raw
    }

    private fun validPort(value: String): Boolean = value.all { it.isDigit() } && value.toIntOrNull() in 1..65_535

    private fun parseIpv4(value: String): Inet4Address? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
            if (part.length > 1 && part.startsWith('0')) return null
            val number = part.toIntOrNull() ?: return null
            if (number !in 0..255) return null
            bytes[index] = number.toByte()
        }
        return InetAddress.getByAddress(bytes) as Inet4Address
    }

    /** Strict IPv6 literal parser, deliberately independent from system DNS parsing. */
    private fun parseIpv6(value: String): Inet6Address? {
        if (!value.contains(':') || value.length > 45 || value.contains('%') || value.contains(":::")) return null
        if (!value.all { it in "0123456789abcdefABCDEF:." }) return null
        val compressedAt = value.indexOf("::")
        if (compressedAt >= 0 && value.indexOf("::", compressedAt + 2) >= 0) return null
        if (compressedAt < 0 && (value.startsWith(':') || value.endsWith(':'))) return null

        val headRaw = if (compressedAt < 0) value else value.substring(0, compressedAt)
        val tailRaw = if (compressedAt < 0) "" else value.substring(compressedAt + 2)
        val head = if (headRaw.isEmpty()) emptyList() else headRaw.split(':')
        val tail = if (compressedAt < 0 || tailRaw.isEmpty()) emptyList() else tailRaw.split(':')
        val all = head + tail
        if (all.any { it.isEmpty() }) return null

        val groups = ArrayList<Int>(8)
        for ((index, token) in all.withIndex()) {
            if (token.contains('.')) {
                if (index != all.lastIndex) return null
                val v4 = parseIpv4(token) ?: return null
                val bytes = v4.address
                groups.add(((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff))
                groups.add(((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff))
            } else {
                if (token.length !in 1..4) return null
                val group = token.toIntOrNull(16) ?: return null
                groups.add(group)
            }
        }
        if (compressedAt < 0 && groups.size != 8) return null
        if (compressedAt >= 0 && groups.size >= 8) return null

        val expanded = ArrayList<Int>(8)
        if (compressedAt < 0) {
            expanded.addAll(groups)
        } else {
            val headGroups = ArrayList<Int>()
            for (token in head) {
                if (token.contains('.')) return null
                headGroups.add(token.toIntOrNull(16) ?: return null)
            }
            expanded.addAll(headGroups)
            repeat(8 - groups.size) { expanded.add(0) }
            val tailGroups = groups.drop(headGroups.size)
            expanded.addAll(tailGroups)
        }
        if (expanded.size != 8) return null
        val bytes = ByteArray(16)
        expanded.forEachIndexed { index, group ->
            bytes[index * 2] = (group shr 8).toByte()
            bytes[index * 2 + 1] = group.toByte()
        }
        return InetAddress.getByAddress(bytes) as Inet6Address
    }

    private fun canonicalIpv6(bytes: ByteArray): String {
        val groups = IntArray(8) { index ->
            ((bytes[index * 2].toInt() and 0xff) shl 8) or (bytes[index * 2 + 1].toInt() and 0xff)
        }
        var bestStart = -1
        var bestLength = 0
        var cursor = 0
        while (cursor < groups.size) {
            if (groups[cursor] != 0) {
                cursor++
                continue
            }
            val start = cursor
            while (cursor < groups.size && groups[cursor] == 0) cursor++
            val length = cursor - start
            if (length >= 2 && length > bestLength) {
                bestStart = start
                bestLength = length
            }
        }
        val render = { start: Int, end: Int ->
            (start until end).joinToString(":") { groups[it].toString(16) }
        }
        if (bestStart < 0) return render(0, groups.size)
        val left = render(0, bestStart)
        val right = render(bestStart + bestLength, groups.size)
        return when {
            left.isEmpty() && right.isEmpty() -> "::"
            left.isEmpty() -> "::$right"
            right.isEmpty() -> "$left::"
            else -> "$left::$right"
        }
    }

    private fun isPublicIpv4(bytes: List<Int>): Boolean {
        val a = bytes[0]
        val b = bytes[1]
        val c = bytes[2]
        return when {
            a == 0 || a >= 224 -> false
            a == 10 || a == 127 -> false
            a == 100 && b in 64..127 -> false // RFC 6598 shared address space
            a == 169 && b == 254 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && b == 0 && c in setOf(0, 2) -> false
            a == 192 && b == 88 && c == 99 -> false
            a == 192 && b == 168 -> false
            a == 198 && b in 18..19 -> false
            a == 198 && b == 51 && c == 100 -> false
            a == 203 && b == 0 && c == 113 -> false
            a == 100 && b == 100 && c == 100 -> false // Alibaba Cloud metadata range
            else -> true
        }
    }

    private fun isPublicIpv6(bytes: List<Int>): Boolean {
        val uniqueLocal = (bytes[0] and 0xfe) == 0xfc
        val documentation = bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8
        val orchid = bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x00 &&
            (bytes[3] and 0xf0) in setOf(0x10, 0x20)
        val discardOnly = bytes.take(8) == listOf(0x01, 0x00, 0, 0, 0, 0, 0, 0)
        val ipv4Mapped = bytes.take(10).all { it == 0 } && bytes[10] == 0xff && bytes[11] == 0xff
        val ipv4Compatible = bytes.take(12).all { it == 0 }
        // These transition prefixes can encapsulate an RFC1918, loopback or link-local
        // IPv4 destination. They are never useful CF edge candidates, so reject them.
        val sixToFour = bytes[0] == 0x20 && bytes[1] == 0x02
        val teredo = bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0 && bytes[3] == 0
        val wellKnownNat64 = bytes[0] == 0 && bytes[1] == 0x64 && bytes[2] == 0xff && bytes[3] == 0x9b &&
            bytes.drop(4).take(8).all { it == 0 }
        val localUseNat64 = bytes[0] == 0 && bytes[1] == 0x64 && bytes[2] == 0xff && bytes[3] == 0x9b &&
            bytes[4] == 0 && bytes[5] == 1
        return !uniqueLocal && !documentation && !orchid && !discardOnly && !ipv4Mapped && !ipv4Compatible &&
            !sixToFour && !teredo && !wellKnownNat64 && !localUseNat64
    }

    private fun jsonValues(value: Any?): List<String> {
        val output = ArrayList<String>()
        var visited = 0

        fun visit(node: Any?, depth: Int) {
            if (depth > MAX_JSON_DEPTH) throw IpSourceException("JSON 嵌套层级不能超过 $MAX_JSON_DEPTH")
            if (++visited > MAX_JSON_NODES) throw IpSourceException("JSON 节点不能超过 $MAX_JSON_NODES")
            when (node) {
                is String -> output.add(node)
                is List<*> -> node.forEach { visit(it, depth + 1) }
                is Map<*, *> -> {
                    val normalized = node.entries.associate { (key, item) ->
                        key?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty() to item
                    }
                    // Preserve the documented key preference but never traverse
                    // an overlapping key twice (for example, `ips`).
                    val handled = HashSet<String>()
                    for (key in jsonIpKeys) {
                        handled.add(key)
                        normalized[key]?.let { visit(it, depth + 1) }
                    }
                    for (key in jsonListKeys) {
                        if (handled.add(key)) normalized[key]?.let { visit(it, depth + 1) }
                    }
                }
            }
        }

        visit(value, 0)
        return output
    }

    private fun csvValues(text: String): Pair<List<String>, String>? {
        val sample = text.take(8192)
        if (!sample.contains('\n') && listOf(',', '\t', ';').none { sample.contains(it) }) return null
        val counts = mapOf(
            ',' to sample.count { it == ',' },
            '\t' to sample.count { it == '\t' },
            ';' to sample.count { it == ';' }
        )
        val delimiter = counts.maxByOrNull { it.value }?.key ?: ','
        if ((counts[delimiter] ?: 0) == 0) return null
        val rows = parseDelimited(text, delimiter).filter { row -> row.any { it.isNotBlank() } }
        if (rows.isEmpty()) return null
        val headers = rows.first().map { it.trim().lowercase(Locale.ROOT) }
        val indexes = headers.mapIndexedNotNull { index, header -> index.takeIf { header in csvIpHeaders } }
        val values = if (indexes.isNotEmpty()) {
            rows.drop(1).flatMap { row -> indexes.mapNotNull { index -> row.getOrNull(index) } }
        } else {
            rows.flatten()
        }
        return values to if (delimiter == '\t') "TSV" else "CSV"
    }

    private fun parseDelimited(text: String, delimiter: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                quoted && ch == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    cell.append('"')
                    index++
                }
                ch == '"' -> quoted = !quoted
                !quoted && ch == delimiter -> {
                    row.add(cell.toString())
                    cell.setLength(0)
                }
                !quoted && ch == '\n' -> {
                    row.add(cell.toString().trimEnd('\r'))
                    cell.setLength(0)
                    rows.add(row)
                    row = ArrayList()
                }
                else -> cell.append(ch)
            }
            index++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString().trimEnd('\r'))
            rows.add(row)
        }
        return rows
    }

    private fun plainValues(text: String): List<String> {
        val output = ArrayList<String>()
        for (rawLine in text.lineSequence()) {
            var line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#') || line.startsWith(';') || line.startsWith("//")) continue
            line = line.replace(Regex("\\s+#.*$"), "")
            line = line.replace(Regex("\\s+;.*$"), "")
            if (line.startsWith("ip ", ignoreCase = true) || line.startsWith("address ", ignoreCase = true)) {
                line = line.substringAfter(' ').trim()
            }
            output.addAll(line.split(Regex("[\\s,;|]+" )).filter { it.isNotEmpty() })
        }
        return output
    }

    private fun tryBase64(text: String): String? {
        val compact = text.filterNot { it.isWhitespace() }
        if (compact.length < 16 || compact.length % 4 == 1 || compact.contains('.') || compact.contains(':')) return null
        if (!Regex("^[A-Za-z0-9_+/]*={0,2}$").matches(compact)) return null
        val normalized = compact.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return try {
            decodeBytes(Base64.getDecoder().decode(padded))
        } catch (_: Exception) {
            null
        }
    }

    /** Small strict JSON parser kept local so the IP import layer has no domain-source dependency. */
    private object IpJson {
        fun parse(text: String): Any? = Parser(text).parse()

        private class Parser(private val source: String) {
            private var index = 0
            private var nodes = 0

            fun parse(): Any? {
                val value = value(0)
                whitespace()
                if (index != source.length) error("trailing JSON")
                return value
            }

            private fun value(depth: Int): Any? {
                whitespace()
                if (index >= source.length) error("unexpected end")
                if (depth > MAX_JSON_DEPTH) error("JSON nesting limit")
                if (++nodes > MAX_JSON_NODES) error("JSON node limit")
                return when (source[index]) {
                    '{' -> obj(depth + 1)
                    '[' -> array(depth + 1)
                    '"' -> string()
                    't' -> literal("true", true)
                    'f' -> literal("false", false)
                    'n' -> literal("null", null)
                    else -> number()
                }
            }

            private fun obj(depth: Int): Map<String, Any?> {
                expect('{')
                val result = LinkedHashMap<String, Any?>()
                whitespace()
                if (peek('}')) { index++; return result }
                while (true) {
                    whitespace()
                    val key = string()
                    whitespace()
                    expect(':')
                    result[key] = value(depth)
                    whitespace()
                    if (peek('}')) { index++; return result }
                    expect(',')
                }
            }

            private fun array(depth: Int): List<Any?> {
                expect('[')
                val result = ArrayList<Any?>()
                whitespace()
                if (peek(']')) { index++; return result }
                while (true) {
                    result.add(value(depth))
                    whitespace()
                    if (peek(']')) { index++; return result }
                    expect(',')
                }
            }

            private fun string(): String {
                expect('"')
                val result = StringBuilder()
                while (index < source.length) {
                    val ch = source[index++]
                    if (ch == '"') return result.toString()
                    if (ch != '\\') {
                        if (ch.code < 0x20) error("control character in string")
                        result.append(ch)
                        continue
                    }
                    if (index >= source.length) error("bad escape")
                    when (val escaped = source[index++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            if (index + 4 > source.length) error("bad unicode escape")
                            result.append(source.substring(index, index + 4).toInt(16).toChar())
                            index += 4
                        }
                        else -> error("bad escape")
                    }
                }
                error("unterminated string")
            }

            private fun number(): Number {
                val start = index
                while (index < source.length && source[index] in "-+0123456789.eE") index++
                if (start == index) error("expected value")
                val raw = source.substring(start, index)
                if (!Regex("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?").matches(raw)) error("bad number")
                return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: error("bad number")
            }

            private fun <T> literal(word: String, value: T): T {
                if (!source.startsWith(word, index)) error("bad literal")
                index += word.length
                return value
            }

            private fun whitespace() { while (index < source.length && source[index].isWhitespace()) index++ }
            private fun peek(ch: Char): Boolean = index < source.length && source[index] == ch
            private fun expect(ch: Char) { if (!peek(ch)) error("expected $ch"); index++ }
        }
    }
}

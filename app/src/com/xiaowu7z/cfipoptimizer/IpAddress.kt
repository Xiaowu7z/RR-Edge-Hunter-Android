package com.xiaowu7z.cfipoptimizer

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Literal IP validation used only by the optional Cloudflare DNS output. */
object IpAddress {
    fun normalizeIp(value: String): String {
        val raw = value.trim()
        require(raw.isNotEmpty()) { "IP 为空" }
        val address = parseLiteralAddress(raw) ?: throw IllegalArgumentException("不是有效 IP 地址")
        require(isPublicAddress(address)) { "仅允许公网 IP 地址" }
        return canonicalAddress(address)
    }

    /** Literal-only parser: malformed input is never sent to DNS for resolution. */
    internal fun parseLiteralAddress(value: String): InetAddress? =
        parseIpv4(value) ?: parseIpv6(value)

    internal fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return false
        val bytes = address.address.map { it.toInt() and 0xff }
        return when (address) {
            is Inet4Address -> isPublicIpv4(bytes)
            is Inet6Address -> isPublicIpv6(bytes)
            else -> false
        }
    }

    private fun canonicalAddress(address: InetAddress): String = when (address) {
        is Inet4Address -> address.address.joinToString(".") { (it.toInt() and 0xff).toString() }
        is Inet6Address -> canonicalIpv6(address.address)
        else -> error("unsupported address family")
    }

    private fun parseIpv4(value: String): Inet4Address? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null
            if (part.length > 1 && part.startsWith('0')) return null
            val number = part.toIntOrNull() ?: return null
            if (number !in 0..255) return null
            bytes[index] = number.toByte()
        }
        return InetAddress.getByAddress(bytes) as Inet4Address
    }

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
        if (all.any(String::isEmpty)) return null

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
                groups.add(token.toIntOrNull(16) ?: return null)
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
            expanded.addAll(groups.drop(headGroups.size))
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
            a == 100 && b in 64..127 -> false
            a == 169 && b == 254 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && b == 0 && c in setOf(0, 2) -> false
            a == 192 && b == 88 && c == 99 -> false
            a == 192 && b == 168 -> false
            a == 198 && b in 18..19 -> false
            a == 198 && b == 51 && c == 100 -> false
            a == 203 && b == 0 && c == 113 -> false
            else -> true
        }
    }

    private fun isPublicIpv6(bytes: List<Int>): Boolean {
        val uniqueLocal = (bytes[0] and 0xfe) == 0xfc
        val documentation = bytes.take(4) == listOf(0x20, 0x01, 0x0d, 0xb8)
        val orchid = bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0 &&
            (bytes[3] and 0xf0) in setOf(0x10, 0x20)
        val discardOnly = bytes.take(8) == listOf(0x01, 0x00, 0, 0, 0, 0, 0, 0)
        val ipv4Mapped = bytes.take(10).all { it == 0 } && bytes[10] == 0xff && bytes[11] == 0xff
        val ipv4Compatible = bytes.take(12).all { it == 0 }
        val sixToFour = bytes[0] == 0x20 && bytes[1] == 0x02
        val teredo = bytes.take(4) == listOf(0x20, 0x01, 0, 0)
        return !uniqueLocal && !documentation && !orchid && !discardOnly &&
            !ipv4Mapped && !ipv4Compatible && !sixToFour && !teredo
    }
}

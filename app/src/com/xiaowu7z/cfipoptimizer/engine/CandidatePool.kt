package com.xiaowu7z.cfipoptimizer.engine

import com.xiaowu7z.cfipoptimizer.IpSources
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

data class CandidateSeed(val ip: String, val source: String)

data class CandidatePoolSelection(
    val candidates: List<CandidateSeed>,
    val importedCount: Int,
    val acceptedImported: Int,
    val ignoredUnsafeOrNonPublic: Int,
    val ignoredWrongFamily: Int,
    val importedSampled: Boolean
) {
    /** Source-compatible diagnostic alias retained for the first 1.0.0 API. */
    val ignoredOutsideCloudflare: Int get() = ignoredUnsafeOrNonPublic
}

/**
 * Cloudflare 优选候选池：公开测速域名 DNS 与官方 CIDR 轮转样本构成默认池。
 * 用户主动导入的任意公网 IP 无需与 DNS 求交，也无需预先声明为 CF；它只
 * 是受限候选，最终仍须通过严格 TLS/SNI/真实 peer/CF-RAY 下载验证才能输出。
 */
object CandidatePool {
    const val MAX_V4_CANDIDATES = 100
    const val MAX_V6_CANDIDATES = 100
    const val MAX_IMPORTED_PER_FAMILY = 60
    const val MAX_IMPORTED_V6 = 60

    fun build(
        snapshot: AuthorizedHostSnapshot,
        imported: Collection<String>,
        family: String,
        includeOfficialSamples: Boolean = true,
        snapshotSource: String = "当前DNS",
        sampleSeed: Long = 0L
    ): CandidatePoolSelection {
        val familyLimit = if (family == "IPv6") MAX_V6_CANDIDATES else MAX_V4_CANDIDATES
        val selected = LinkedHashMap<String, CandidateSeed>()
        fun add(ip: String, source: String) {
            if (selected.size < familyLimit) selected.putIfAbsent(addressKey(ip), CandidateSeed(ip, source))
        }

        snapshot.forFamily(family).forEach { raw ->
            val address = literal(raw) ?: return@forEach
            if (matchesFamily(address, family) && CfRanges.isCloudflare(address)) {
                add(canonical(address), snapshotSource)
            }
        }

        val validImported = ArrayList<String>()
        var unsafe = 0
        var wrongFamily = 0
        val importedKeys = LinkedHashSet<String>()
        imported.forEach { raw ->
            val address = literal(raw)
            if (address == null) {
                unsafe++
                return@forEach
            }
            val key = address.address.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (!importedKeys.add(key)) return@forEach
            when {
                !matchesFamily(address, family) -> wrongFamily++
                else -> validImported.add(canonical(address))
            }
        }
        // Reserve room for DNS seeds and a small official-range sample in both
        // families. IPv6 /32 ranges are especially broad, so imported/DNS seeds
        // remain the more valuable majority while experiments stay bounded.
        val importLimit = if (family == "IPv6") MAX_IMPORTED_V6 else MAX_IMPORTED_PER_FAMILY
        val sampledImported = spread(validImported, importLimit)
        sampledImported.forEach { add(it, "用户IP池") }

        if (includeOfficialSamples) {
            // Android 不展开大网段。按协议族和本轮 seed 取有界轮转样本，
            // 在每族 100 个总上限内覆盖更多入口，同时保持扫描有界。
            val perRange = if (family == "IPv6") 15 else 7
            CfRanges.sampleOfficial(family, perRange, familyLimit, sampleSeed).forEach { add(it, "CF官方网段轮转抽样") }
        }

        return CandidatePoolSelection(
            candidates = selected.values.toList(),
            importedCount = importedKeys.size,
            acceptedImported = validImported.size,
            ignoredUnsafeOrNonPublic = unsafe,
            ignoredWrongFamily = wrongFamily,
            importedSampled = validImported.size > sampledImported.size
        )
    }

    private fun spread(values: List<String>, limit: Int): List<String> {
        if (values.size <= limit) return values
        if (limit <= 1) return values.take(limit)
        val last = values.lastIndex.toLong()
        return (0 until limit).map { index -> values[(last * index / (limit - 1)).toInt()] }.distinct()
    }

    private fun literal(value: String): InetAddress? = try {
        val canonical = IpSources.normalizeIp(value)
        InetAddress.getByName(canonical)
    } catch (_: Exception) {
        null
    }

    private fun matchesFamily(address: InetAddress, family: String): Boolean = when (family) {
        "IPv4" -> address is Inet4Address
        "IPv6" -> address is Inet6Address
        else -> false
    }

    private fun canonical(address: InetAddress): String = IpSources.canonicalAddress(address)
    private fun addressKey(value: String): String = literal(value)?.address
        ?.joinToString("") { "%02x".format(it.toInt() and 0xff) } ?: value
}

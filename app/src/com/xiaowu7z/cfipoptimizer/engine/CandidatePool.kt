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
    val ignoredOutsideCloudflare: Int,
    val ignoredWrongFamily: Int,
    val importedSampled: Boolean
)

/**
 * Argo 优选候选池：当前 DNS 用作可信种子，导入地址不再与 DNS 求交；所有
 * 实际候选仍必须位于 Cloudflare 官方 CIDR，并受每协议族上限约束。
 */
object CandidatePool {
    const val MAX_V4_CANDIDATES = 48
    const val MAX_V6_CANDIDATES = 24
    const val MAX_IMPORTED_PER_FAMILY = 24
    const val MAX_IMPORTED_V6 = 10

    fun build(
        snapshot: AuthorizedHostSnapshot,
        imported: Collection<String>,
        family: String,
        includeOfficialSamples: Boolean = true
    ): CandidatePoolSelection {
        val familyLimit = if (family == "IPv6") MAX_V6_CANDIDATES else MAX_V4_CANDIDATES
        val selected = LinkedHashMap<String, CandidateSeed>()
        fun add(ip: String, source: String) {
            if (selected.size < familyLimit) selected.putIfAbsent(addressKey(ip), CandidateSeed(ip, source))
        }

        snapshot.forFamily(family).forEach { raw ->
            val address = literal(raw) ?: return@forEach
            if (matchesFamily(address, family) && CfRanges.isCloudflare(address)) {
                add(canonical(address), "当前DNS")
            }
        }

        val validImported = ArrayList<String>()
        var outside = 0
        var wrongFamily = 0
        val importedKeys = LinkedHashSet<String>()
        imported.forEach { raw ->
            val address = literal(raw) ?: return@forEach
            val key = address.address.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (!importedKeys.add(key)) return@forEach
            when {
                !matchesFamily(address, family) -> wrongFamily++
                !CfRanges.isCloudflare(address) -> outside++
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
            val perRange = if (family == "IPv6") 2 else 2
            CfRanges.sampleOfficial(family, perRange, familyLimit).forEach { add(it, "CF官方网段抽样") }
        }

        return CandidatePoolSelection(
            candidates = selected.values.toList(),
            importedCount = importedKeys.size,
            acceptedImported = validImported.size,
            ignoredOutsideCloudflare = outside,
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

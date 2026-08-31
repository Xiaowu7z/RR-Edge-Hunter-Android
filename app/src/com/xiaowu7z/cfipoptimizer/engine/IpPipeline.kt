package com.xiaowu7z.cfipoptimizer.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext
import kotlin.math.round
import java.util.concurrent.atomic.AtomicInteger

/** A native-IP result.  Every Full failure is retained as 0 Mbps. */
data class IpMetric(
    val ip: String,
    val family: String,
    val source: String,
    val pre: ProbeEngine.ProbeResult? = null,
    val micro: ProbeEngine.ProbeResult? = null,
    val full: List<ProbeEngine.ProbeResult> = emptyList(),
    val minCompleteMbps: Double = 0.0,
    val avgCompleteMbps: Double = 0.0,
    val maxCompleteMbps: Double = 0.0,
    val fullSuccessRatePct: Double = 0.0,
    val variationPct: Double = 0.0,
    val medianTtfbMs: Double = -1.0,
    val floorMbps: Double = 0.0,
    val primaryPop: String = "",
    val popDrift: Boolean = false,
    val edgeScore: Int = 0
) {
    val stability: String get() = when {
        fullSuccessRatePct >= 90.0 && variationPct <= 15.0 -> "优秀"
        fullSuccessRatePct >= 75.0 && variationPct <= 30.0 -> "良好"
        fullSuccessRatePct >= 50.0 -> "一般"
        else -> "较差"
    }
}

/**
 * IP-native staged runner.
 *
 * The only accepted candidates are supplied by [AuthorizedHost] after the
 * imported list has been intersected with the current DNS allocation.  This
 * runner intentionally has no range expansion, arbitrary host override, proxy
 * or DNS-record writing capability.
 */
object IpPipeline {
    private const val PRE_TIMEOUT_SECONDS = 8
    private const val MICRO_TIMEOUT_SECONDS = 12
    private const val FULL_TIMEOUT_SECONDS = 30

    data class ModeParams(
        val preBytes: Long,
        val microBytes: Long,
        val fullBytes: Long,
        val microLimit: Int,
        val finalLimit: Int,
        val fullRounds: Int,
        val preConcurrency: Int,
        val microConcurrency: Int,
        val fullConcurrency: Int
    )

    val BALANCED = ModeParams(
        preBytes = 128_000L,
        microBytes = 1_000_000L,
        fullBytes = 8_000_000L,
        microLimit = 16,
        finalLimit = 8,
        fullRounds = 3,
        preConcurrency = 8,
        microConcurrency = 4,
        fullConcurrency = 1
    )

    val ASIA_HUNT = ModeParams(
        preBytes = 96_000L,
        microBytes = 1_000_000L,
        fullBytes = 8_000_000L,
        microLimit = 24,
        finalLimit = 12,
        fullRounds = 3,
        preConcurrency = 8,
        microConcurrency = 4,
        fullConcurrency = 1
    )

    data class Candidate(val ip: String, val source: String)

    data class Stage(val name: String, val current: Int = 0, val total: Int = 0)

    data class FamilyResult(
        val ranked: List<IpMetric>,
        val asiaRanked: List<IpMetric>,
        val popCounts: Map<String, Int>,
        val invalid: Boolean = false
    )

    fun popPriority(pop: String): Int = when (pop.uppercase()) {
        "HKG" -> 5
        "NRT" -> 4
        "SIN" -> 3
        "ICN" -> 2
        "TPE" -> 1
        else -> 0
    }

    fun estimateTrafficUpperBoundMb(candidateCount: Int, params: ModeParams): Double {
        if (candidateCount <= 0) return 0.0
        val micro = minOf(candidateCount, params.microLimit)
        val final = minOf(micro, params.finalLimit)
        val bytes = candidateCount * params.preBytes + micro * params.microBytes +
            final * params.fullRounds * params.fullBytes
        return bytes / 1_000_000.0
    }

    suspend fun runFamily(
        testHost: String,
        family: String,
        candidates: List<Candidate>,
        params: ModeParams,
        asiaHunt: Boolean,
        networkInvalid: () -> Boolean = { false },
        onStage: (Stage) -> Unit = {},
        log: (String) -> Unit = {}
    ): FamilyResult = coroutineScope {
        val deduped = candidates
            .filter { ProbeEngine.familyOf(it.ip) == family }
            .distinctBy { it.ip }
        if (deduped.isEmpty()) return@coroutineScope FamilyResult(emptyList(), emptyList(), emptyMap())

        fun checkNetwork(): Boolean = networkInvalid().also {
            if (it) log("网络环境已变化，本轮结果作废")
        }

        onStage(Stage("预检", 0, deduped.size))
        val preDone = AtomicInteger(0)
        val preResults = parallel(deduped, params.preConcurrency) { candidate ->
            coroutineContext.ensureActive()
            if (checkNetwork()) {
                candidate to failed(candidate.ip, "网络变化")
            } else {
                val result = ProbeEngine.probeDownload(
                    targetIp = candidate.ip,
                    bytes = params.preBytes,
                    timeoutSec = PRE_TIMEOUT_SECONDS,
                    testHost = testHost,
                    includeTrace = asiaHunt,
                    log = log
                )
                onStage(Stage("预检", preDone.incrementAndGet(), deduped.size))
                candidate to result
            }
        }.toMap()
        if (checkNetwork()) return@coroutineScope FamilyResult(emptyList(), emptyList(), emptyMap(), invalid = true)

        val preEligible = deduped.filter { preResults[it]?.ok == true }
        if (preEligible.isEmpty()) {
            val failures = deduped.map { metric(it, preResults[it], null, emptyList()) }
            return@coroutineScope FamilyResult(rank(failures), rankAsia(failures), emptyMap())
        }
        val microCandidates = chooseForMicro(preEligible, preResults, params.microLimit, asiaHunt)

        onStage(Stage("小流量筛选", 0, microCandidates.size))
        val microDone = AtomicInteger(0)
        val microResults = parallel(microCandidates, params.microConcurrency) { candidate ->
            coroutineContext.ensureActive()
            if (checkNetwork()) {
                candidate to failed(candidate.ip, "网络变化")
            } else {
                val result = ProbeEngine.probeDownload(
                    targetIp = candidate.ip,
                    bytes = params.microBytes,
                    timeoutSec = MICRO_TIMEOUT_SECONDS,
                    testHost = testHost,
                    includeTrace = false,
                    log = log
                )
                onStage(Stage("小流量筛选", microDone.incrementAndGet(), microCandidates.size))
                candidate to result
            }
        }.toMap()
        if (checkNetwork()) return@coroutineScope FamilyResult(emptyList(), emptyList(), emptyMap(), invalid = true)

        val finalCandidates = chooseForFull(microCandidates, preResults, microResults, params.finalLimit, asiaHunt)
        if (finalCandidates.isEmpty()) {
            // Keep every original candidate visible.  Candidates rejected by a
            // staged gate have no Full samples and therefore carry a zero score
            // instead of quietly disappearing from the audit trail.
            val metrics = deduped.map { candidate ->
                metric(candidate, preResults[candidate], microResults[candidate], emptyList())
            }
            return@coroutineScope FamilyResult(rank(metrics), rankAsia(metrics), emptyMap())
        }

        // Round-major scheduling gives each finalist an early first result while
        // still guaranteeing an exact fixed number of Full attempts per IP.
        val schedule = buildList {
            repeat(params.fullRounds) { round ->
                finalCandidates.forEach { add(round to it) }
            }
        }
        onStage(Stage("完整测速（固定 ${params.fullRounds} 轮）", 0, schedule.size))
        val fullDone = AtomicInteger(0)
        val fullResults = parallel(schedule, params.fullConcurrency) { (_, candidate) ->
            coroutineContext.ensureActive()
            if (checkNetwork()) {
                candidate to failed(candidate.ip, "网络变化")
            } else {
                val result = ProbeEngine.probeDownload(
                    targetIp = candidate.ip,
                    bytes = params.fullBytes,
                    timeoutSec = FULL_TIMEOUT_SECONDS,
                    testHost = testHost,
                    includeTrace = true,
                    log = log
                )
                onStage(Stage("完整测速（固定 ${params.fullRounds} 轮）", fullDone.incrementAndGet(), schedule.size))
                candidate to result
            }
        }
        if (checkNetwork()) return@coroutineScope FamilyResult(emptyList(), emptyList(), emptyMap(), invalid = true)

        val perIpFull = LinkedHashMap<String, MutableList<ProbeEngine.ProbeResult>>()
        fullResults.forEach { (candidate, probe) -> perIpFull.getOrPut(candidate.ip) { mutableListOf() }.add(probe) }
        // Return all candidates, not only finalists.  A failed Pre/Micro gate
        // is evidence too; its empty Full series remains an explicit zero in
        // the result rather than being silently dropped.
        val metrics = deduped.map { candidate ->
            metric(candidate, preResults[candidate], microResults[candidate], perIpFull[candidate.ip].orEmpty())
        }
        val pops = LinkedHashMap<String, Int>()
        metrics.flatMap { it.full }.filter { it.ok && it.colo.isNotBlank() }
            .forEach { pops[it.colo.uppercase()] = (pops[it.colo.uppercase()] ?: 0) + 1 }
        FamilyResult(rank(metrics), rankAsia(metrics), pops)
    }

    private fun chooseForMicro(
        candidates: List<Candidate>,
        pre: Map<Candidate, ProbeEngine.ProbeResult>,
        limit: Int,
        asiaHunt: Boolean
    ): List<Candidate> = candidates.sortedWith(
        compareByDescending<Candidate> { if (asiaHunt) popPriority(pre[it]?.colo.orEmpty()) else 0 }
            .thenByDescending { pre[it]?.completeTransferMbps ?: 0.0 }
            .thenBy { it.ip }
    ).take(limit)

    private fun chooseForFull(
        candidates: List<Candidate>,
        pre: Map<Candidate, ProbeEngine.ProbeResult>,
        micro: Map<Candidate, ProbeEngine.ProbeResult>,
        limit: Int,
        asiaHunt: Boolean
    ): List<Candidate> = candidates.filter { micro[it]?.ok == true }.sortedWith(
        compareByDescending<Candidate> { if (asiaHunt) popPriority(pre[it]?.colo.orEmpty()) else 0 }
            .thenByDescending { micro[it]?.completeTransferMbps ?: 0.0 }
            .thenBy { it.ip }
    ).take(limit)

    private fun failed(ip: String, message: String) = ProbeEngine.ProbeResult(ok = false, targetIp = ip, error = message)

    private fun metric(
        candidate: Candidate,
        pre: ProbeEngine.ProbeResult?,
        micro: ProbeEngine.ProbeResult?,
        full: List<ProbeEngine.ProbeResult>
    ): IpMetric {
        val speeds = full.map { if (it.ok) it.completeTransferMbps else 0.0 }
        val fullSuccesses = full.count { it.ok }
        val successRate = if (full.isEmpty()) 0.0 else round(fullSuccesses * 1000.0 / full.size) / 10.0
        val min = speeds.minOrNull() ?: 0.0
        val avg = if (speeds.isEmpty()) 0.0 else speeds.average()
        val max = speeds.maxOrNull() ?: 0.0
        // A single Full failure makes the IP's reliability floor zero.
        val floor = if (full.isEmpty() || full.any { !it.ok }) 0.0 else min
        val variation = variation(speeds)
        val ttfb = median(full.filter { it.ok }.map { it.ttfbMs })
        val pops = full.filter { it.ok }.map { it.colo.uppercase() }.filter { it.isNotBlank() }
        val primary = pops.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { popPriority(it.key) })
            .firstOrNull()?.key.orEmpty()
        val popDrift = pops.distinct().size > 1
        val edgeScore = popPriority(primary) * 10 + if (!popDrift && primary.isNotEmpty()) 1 else 0
        return IpMetric(
            ip = candidate.ip,
            family = ProbeEngine.familyOf(candidate.ip) ?: "未知",
            source = candidate.source,
            pre = pre,
            micro = micro,
            full = full,
            minCompleteMbps = min,
            avgCompleteMbps = avg,
            maxCompleteMbps = max,
            fullSuccessRatePct = successRate,
            variationPct = variation,
            medianTtfbMs = ttfb,
            floorMbps = floor,
            primaryPop = primary,
            popDrift = popDrift,
            edgeScore = edgeScore
        )
    }

    fun rank(metrics: List<IpMetric>): List<IpMetric> = metrics.sortedWith(
        compareByDescending<IpMetric> { it.floorMbps }
            .thenByDescending { it.fullSuccessRatePct }
            .thenByDescending { it.minCompleteMbps }
            .thenByDescending { it.avgCompleteMbps }
            .thenBy { it.variationPct }
            .thenBy { if (it.medianTtfbMs < 0.0) Double.MAX_VALUE else it.medianTtfbMs }
            .thenBy { it.ip }
    )

    fun rankAsia(metrics: List<IpMetric>): List<IpMetric> = metrics.sortedWith(
        compareByDescending<IpMetric> { it.edgeScore }
            .thenBy { it.popDrift }
            .thenByDescending { it.floorMbps }
            .thenByDescending { it.fullSuccessRatePct }
            .thenByDescending { it.avgCompleteMbps }
            .thenBy { it.variationPct }
            .thenBy { it.ip }
    )

    private fun variation(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val avg = values.average()
        if (avg <= 0.0) return 0.0
        return round((values.maxOrNull()!! - values.minOrNull()!!) * 1000.0 / avg) / 10.0
    }

    private fun median(values: List<Double>): Double {
        val valid = values.filter { it > 0.0 && it.isFinite() }.sorted()
        if (valid.isEmpty()) return -1.0
        return if (valid.size % 2 == 1) valid[valid.size / 2] else (valid[valid.size / 2 - 1] + valid[valid.size / 2]) / 2.0
    }

    private suspend fun <T, R> parallel(
        items: List<T>,
        concurrency: Int,
        block: suspend (T) -> R
    ): List<R> = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        items.map { item -> async { semaphore.withPermit { block(item) } } }.awaitAll()
    }
}

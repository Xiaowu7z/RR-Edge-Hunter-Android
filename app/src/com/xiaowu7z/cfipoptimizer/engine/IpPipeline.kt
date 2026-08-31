package com.xiaowu7z.cfipoptimizer.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext
import kotlin.math.round
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/** A native-IP result.  Every Full failure is retained as 0 Mbps. */
data class IpMetric(
    val ip: String,
    val family: String,
    val source: String,
    val route: ProbeEngine.ArgoRouteResult? = null,
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
    // A single lucky success is not enough for a Telegram/video recommendation.
    // With the fixed three Full rounds this requires at least two successes.
    val isArgoUsable: Boolean get() = route?.ok == true && full.isNotEmpty() && fullSuccessRatePct >= 66.0
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
 * Every candidate is first fixed to the user's Argo hostname and must pass
 * normal TLS hostname verification. Throughput is then measured against
 * speed.cloudflare.com on the same IP because an Argo hostname does not expose
 * Cloudflare's /__down endpoint.
 */
object IpPipeline {
    private const val ROUTE_TIMEOUT_SECONDS = 8
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
        preBytes = 64_000L,
        microBytes = 512_000L,
        fullBytes = 2_000_000L,
        microLimit = 12,
        finalLimit = 5,
        fullRounds = 3,
        preConcurrency = 6,
        microConcurrency = 3,
        fullConcurrency = 1
    )

    val ASIA_HUNT = ModeParams(
        preBytes = 64_000L,
        microBytes = 512_000L,
        fullBytes = 2_000_000L,
        microLimit = 16,
        finalLimit = 6,
        fullRounds = 3,
        preConcurrency = 6,
        microConcurrency = 3,
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
        argoHost: String,
        wsPath: String,
        family: String,
        candidates: List<Candidate>,
        params: ModeParams,
        asiaHunt: Boolean,
        networkInvalid: () -> Boolean = { false },
        onStage: (Stage) -> Unit = {},
        log: (String) -> Unit = {}
    ): FamilyResult = coroutineScope {
        val familyLimit = if (family == "IPv6") CandidatePool.MAX_V6_CANDIDATES else CandidatePool.MAX_V4_CANDIDATES
        val deduped = candidates
            .filter { ProbeEngine.familyOf(it.ip) == family }
            .filter { candidate ->
                try { CfRanges.isCloudflare(InetAddress.getByName(candidate.ip)) } catch (_: Exception) { false }
            }
            .distinctBy { it.ip }
            .take(familyLimit)
        if (deduped.isEmpty()) return@coroutineScope FamilyResult(emptyList(), emptyList(), emptyMap())

        fun checkNetwork(): Boolean = networkInvalid().also {
            if (it) log("网络环境已变化，本轮结果作废")
        }

        onStage(Stage("Argo 入口兼容验证", 0, deduped.size))
        val routeDone = AtomicInteger(0)
        val routeResults = parallel(deduped, params.preConcurrency) { candidate ->
            coroutineContext.ensureActive()
            if (checkNetwork()) {
                candidate to ProbeEngine.ArgoRouteResult(ok = false, targetIp = candidate.ip, error = "网络变化")
            } else {
                val result = ProbeEngine.probeArgoRoute(
                    targetIp = candidate.ip,
                    argoHost = argoHost,
                    wsPath = wsPath,
                    timeoutSec = ROUTE_TIMEOUT_SECONDS,
                    log = log
                )
                onStage(Stage("Argo 入口兼容验证", routeDone.incrementAndGet(), deduped.size))
                candidate to result
            }
        }.toMap()
        if (checkNetwork()) return@coroutineScope FamilyResult(emptyList(), emptyList(), emptyMap(), invalid = true)

        val routeEligible = deduped.filter { routeResults[it]?.ok == true }
        if (routeEligible.isEmpty()) {
            val failures = deduped.map { metric(it, routeResults[it], null, null, emptyList()) }
            return@coroutineScope FamilyResult(rank(failures), rankAsia(failures), emptyMap())
        }

        onStage(Stage("边缘速度预检", 0, routeEligible.size))
        val preDone = AtomicInteger(0)
        val preResults = parallel(routeEligible, params.preConcurrency) { candidate ->
            coroutineContext.ensureActive()
            if (checkNetwork()) {
                candidate to failed(candidate.ip, "网络变化")
            } else {
                val result = ProbeEngine.probeDownload(
                    targetIp = candidate.ip,
                    bytes = params.preBytes,
                    timeoutSec = PRE_TIMEOUT_SECONDS,
                    testHost = ProbeEngine.SPEED_HOST,
                    includeTrace = false,
                    log = log
                )
                onStage(Stage("边缘速度预检", preDone.incrementAndGet(), routeEligible.size))
                candidate to result
            }
        }.toMap()
        if (checkNetwork()) return@coroutineScope FamilyResult(emptyList(), emptyList(), emptyMap(), invalid = true)

        val preEligible = routeEligible.filter { preResults[it]?.ok == true }
        if (preEligible.isEmpty()) {
            val failures = deduped.map { metric(it, routeResults[it], preResults[it], null, emptyList()) }
            return@coroutineScope FamilyResult(rank(failures), rankAsia(failures), emptyMap())
        }
        val microCandidates = chooseForMicro(preEligible, preResults, routeResults, params.microLimit, asiaHunt)

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
                    testHost = ProbeEngine.SPEED_HOST,
                    includeTrace = false,
                    log = log
                )
                onStage(Stage("小流量筛选", microDone.incrementAndGet(), microCandidates.size))
                candidate to result
            }
        }.toMap()
        if (checkNetwork()) return@coroutineScope FamilyResult(emptyList(), emptyList(), emptyMap(), invalid = true)

        val finalCandidates = chooseForFull(microCandidates, preResults, microResults, routeResults, params.finalLimit, asiaHunt)
        if (finalCandidates.isEmpty()) {
            // Keep every original candidate visible.  Candidates rejected by a
            // staged gate have no Full samples and therefore carry a zero score
            // instead of quietly disappearing from the audit trail.
            val metrics = deduped.map { candidate ->
                metric(candidate, routeResults[candidate], preResults[candidate], microResults[candidate], emptyList())
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
                    testHost = ProbeEngine.SPEED_HOST,
                    includeTrace = false,
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
            metric(candidate, routeResults[candidate], preResults[candidate], microResults[candidate], perIpFull[candidate.ip].orEmpty())
        }
        val pops = LinkedHashMap<String, Int>()
        metrics.mapNotNull { it.route?.takeIf { route -> route.ok && route.colo.isNotBlank() }?.colo }
            .forEach { pop -> pops[pop.uppercase()] = (pops[pop.uppercase()] ?: 0) + 1 }
        FamilyResult(rank(metrics), rankAsia(metrics), pops)
    }

    private fun chooseForMicro(
        candidates: List<Candidate>,
        pre: Map<Candidate, ProbeEngine.ProbeResult>,
        route: Map<Candidate, ProbeEngine.ArgoRouteResult>,
        limit: Int,
        asiaHunt: Boolean
    ): List<Candidate> = candidates.sortedWith(
        compareByDescending<Candidate> { pre[it]?.completeTransferMbps ?: 0.0 }
            .thenByDescending { if (asiaHunt) popPriority(route[it]?.colo.orEmpty()) else 0 }
            .thenBy { it.ip }
    ).take(limit)

    private fun chooseForFull(
        candidates: List<Candidate>,
        pre: Map<Candidate, ProbeEngine.ProbeResult>,
        micro: Map<Candidate, ProbeEngine.ProbeResult>,
        route: Map<Candidate, ProbeEngine.ArgoRouteResult>,
        limit: Int,
        asiaHunt: Boolean
    ): List<Candidate> = candidates.filter { micro[it]?.ok == true }.sortedWith(
        compareByDescending<Candidate> { micro[it]?.completeTransferMbps ?: 0.0 }
            .thenByDescending { if (asiaHunt) popPriority(route[it]?.colo.orEmpty()) else 0 }
            .thenBy { it.ip }
    ).take(limit)

    private fun failed(ip: String, message: String) = ProbeEngine.ProbeResult(ok = false, targetIp = ip, error = message)

    private fun metric(
        candidate: Candidate,
        route: ProbeEngine.ArgoRouteResult?,
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
        val pops = listOfNotNull(route?.takeIf { it.ok }?.colo?.uppercase()).filter { it.isNotBlank() }
        val primary = pops.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { popPriority(it.key) })
            .firstOrNull()?.key.orEmpty()
        val popDrift = pops.distinct().size > 1
        val edgeScore = popPriority(primary) * 10 + if (!popDrift && primary.isNotEmpty()) 1 else 0
        return IpMetric(
            ip = candidate.ip,
            family = ProbeEngine.familyOf(candidate.ip) ?: "未知",
            source = candidate.source,
            route = route,
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
        compareByDescending<IpMetric> { it.isArgoUsable }
            .thenByDescending { it.route?.ok == true }
            .thenByDescending { it.floorMbps }
            .thenByDescending { it.fullSuccessRatePct }
            .thenByDescending { it.minCompleteMbps }
            .thenByDescending { it.avgCompleteMbps }
            .thenBy { it.variationPct }
            .thenBy { if (it.medianTtfbMs < 0.0) Double.MAX_VALUE else it.medianTtfbMs }
            .thenBy { it.ip }
    )

    fun rankAsia(metrics: List<IpMetric>): List<IpMetric> = metrics.sortedWith(
        compareByDescending<IpMetric> { it.isArgoUsable }
            .thenByDescending { it.route?.ok == true }
            .thenByDescending { it.floorMbps }
            .thenByDescending { it.fullSuccessRatePct }
            .thenByDescending { it.minCompleteMbps }
            .thenByDescending { it.avgCompleteMbps }
            .thenBy { it.variationPct }
            .thenByDescending { it.edgeScore }
            .thenBy { it.popDrift }
            .thenBy { if (it.medianTtfbMs < 0.0) Double.MAX_VALUE else it.medianTtfbMs }
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

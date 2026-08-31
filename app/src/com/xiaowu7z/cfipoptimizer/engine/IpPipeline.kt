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
    val routeValidationRequired: Boolean = false,
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
    // 至少两次独立的 1 秒真实下载样本都成功，才允许复制到节点。
    val isNodeUsable: Boolean get() =
        (!routeValidationRequired || route?.ok == true) &&
            full.size >= 2 && full.all { it.ok } && fullSuccessRatePct >= 99.9
    // DNS 写入影响范围更大，只允许已完成复测且可靠下限为正的候选。
    val isDnsSyncEligible: Boolean get() =
        isNodeUsable && full.size >= 2 && floorMbps > 0.0
    // Kept as a source-compatible alias for callers from the first 1.0.0
    // implementation.  The default product is now direct-IP ranking, while an
    // Argo hostname route check is an optional extra gate.
    val isArgoUsable: Boolean get() = isNodeUsable
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
 * The default path fixes speed.cloudflare.com to every candidate and measures
 * that exact TCP peer.  No user hostname is needed.  When [argoHost] is not
 * blank, the same candidates must additionally pass normal TLS/SNI/Host (and
 * optional WebSocket) verification for that hostname before speed testing.
 */
object IpPipeline {
    private const val ROUTE_TIMEOUT_SECONDS = 8
    private const val PRE_TIMEOUT_SECONDS = 5
    private const val SPEED_TIMEOUT_SECONDS = 5

    /**
     * microLimit 表示延迟预筛后进入真实下载的候选数；finalLimit 表示复测数。
     * 保留原字段名是为了兼容 1.0.0 的内部接口，测速已不再下载固定大文件。
     */
    data class ModeParams(
        val preBytes: Long,
        val microBytes: Long,
        val fullBytes: Long,
        val microLimit: Int,
        val finalLimit: Int,
        val fullRounds: Int,
        val preConcurrency: Int,
        val microConcurrency: Int,
        val fullConcurrency: Int,
        val earlyStop: Boolean
    )

    val BALANCED = ModeParams(
        preBytes = 16_000L,
        microBytes = 0L,
        fullBytes = 0L,
        microLimit = 10,
        finalLimit = 2,
        fullRounds = 2,
        preConcurrency = 32,
        microConcurrency = 1,
        fullConcurrency = 1,
        earlyStop = true
    )

    val ASIA_HUNT = ModeParams(
        preBytes = 16_000L,
        microBytes = 0L,
        fullBytes = 0L,
        microLimit = 10,
        finalLimit = 3,
        fullRounds = 2,
        preConcurrency = 32,
        microConcurrency = 1,
        fullConcurrency = 1,
        earlyStop = true
    )

    val MAX_BANDWIDTH = ModeParams(
        preBytes = 16_000L,
        microBytes = 0L,
        fullBytes = 0L,
        microLimit = 10,
        finalLimit = 3,
        fullRounds = 2,
        preConcurrency = 32,
        microConcurrency = 1,
        fullConcurrency = 1,
        earlyStop = false
    )

    /** 兼容旧调用；目标带宽现在直接控制 1 秒测速请求上限。 */
    fun forExpectedMbps(base: ModeParams, expectedMbps: Int): ModeParams {
        expectedMbps.coerceIn(1, 2_000)
        return base
    }

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

    fun estimateTrafficUpperBoundMb(
        candidateCount: Int,
        params: ModeParams,
        expectedMbps: Int = 100
    ): Double {
        if (candidateCount <= 0) return 0.0
        val shortlist = minOf(candidateCount, params.microLimit)
        val confirmations = minOf(shortlist, params.finalLimit)
        val requestBytes = ProbeEngine.speedRequestBytes(expectedMbps, maximum = !params.earlyStop)
        val bytes = candidateCount * params.preBytes +
            (shortlist + confirmations) * requestBytes
        return bytes / 1_000_000.0
    }

    suspend fun runFamily(
        argoHost: String,
        wsPath: String,
        family: String,
        candidates: List<Candidate>,
        params: ModeParams,
        asiaHunt: Boolean,
        argoPort: Int = 443,
        expectedMbps: Int = 100,
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

        val routeValidationRequired = argoHost.isNotBlank()
        val routeResults: Map<Candidate, ProbeEngine.ArgoRouteResult> = if (routeValidationRequired) {
            onStage(Stage("高级节点兼容复核", 0, deduped.size))
            val routeDone = AtomicInteger(0)
            parallel(deduped, minOf(params.preConcurrency, 16)) { candidate ->
                coroutineContext.ensureActive()
                if (checkNetwork()) {
                    candidate to ProbeEngine.ArgoRouteResult(ok = false, targetIp = candidate.ip, error = "网络变化")
                } else {
                    val result = ProbeEngine.probeArgoRoute(
                        targetIp = candidate.ip,
                        argoHost = argoHost,
                        wsPath = wsPath,
                        targetPort = argoPort,
                        timeoutSec = ROUTE_TIMEOUT_SECONDS,
                        log = log
                    )
                    onStage(Stage("高级节点兼容复核", routeDone.incrementAndGet(), deduped.size))
                    candidate to result
                }
            }.toMap()
        } else emptyMap()
        if (checkNetwork()) return@coroutineScope FamilyResult(emptyList(), emptyList(), emptyMap(), invalid = true)

        val routeEligible = if (routeValidationRequired) {
            deduped.filter { routeResults[it]?.ok == true }
        } else deduped
        if (routeEligible.isEmpty()) {
            val failures = deduped.map { metric(it, routeResults[it], true, null, null, emptyList()) }
            return@coroutineScope FamilyResult(rank(failures), rankAsia(failures), emptyMap())
        }

        onStage(Stage("并发延迟预筛", 0, routeEligible.size))
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
                    targetPort = 443,
                    includeTrace = false,
                    log = log
                )
                onStage(Stage("并发延迟预筛", preDone.incrementAndGet(), routeEligible.size))
                candidate to result
            }
        }.toMap()
        if (checkNetwork()) return@coroutineScope FamilyResult(emptyList(), emptyList(), emptyMap(), invalid = true)

        val preEligible = routeEligible.filter { preResults[it]?.ok == true }
        if (preEligible.isEmpty()) {
            val failures = deduped.map { metric(it, routeResults[it], routeValidationRequired, preResults[it], null, emptyList()) }
            return@coroutineScope FamilyResult(rank(failures), rankAsia(failures), emptyMap())
        }

        val speedCandidates = chooseForSpeed(preEligible, preResults, params.microLimit)
        val requestBytes = ProbeEngine.speedRequestBytes(expectedMbps, maximum = !params.earlyStop)
        val samples = LinkedHashMap<Candidate, MutableList<ProbeEngine.ProbeResult>>()

        suspend fun sample(candidate: Candidate): ProbeEngine.ProbeResult {
            coroutineContext.ensureActive()
            if (checkNetwork()) return failed(candidate.ip, "网络变化")
            return ProbeEngine.probeSpeedWindow(
                targetIp = candidate.ip,
                requestedBytes = requestBytes,
                sampleMillis = 1_000L,
                timeoutSec = SPEED_TIMEOUT_SECONDS,
                testHost = ProbeEngine.SPEED_HOST,
                targetPort = 443,
                log = log
            )
        }

        var earlyWinner: Candidate? = null
        onStage(Stage("1 秒真实下载测速", 0, speedCandidates.size))
        for ((index, candidate) in speedCandidates.withIndex()) {
            val first = sample(candidate)
            samples.getOrPut(candidate) { mutableListOf() }.add(first)
            onStage(Stage("1 秒真实下载测速", index + 1, speedCandidates.size))
            if (checkNetwork()) return@coroutineScope FamilyResult(emptyList(), emptyList(), emptyMap(), invalid = true)

            if (params.earlyStop && first.ok && first.completeTransferMbps >= expectedMbps) {
                onStage(Stage("达标候选复测", 0, 1))
                val second = sample(candidate)
                samples.getValue(candidate).add(second)
                onStage(Stage("达标候选复测", 1, 1))
                if (second.ok && second.completeTransferMbps >= expectedMbps) {
                    earlyWinner = candidate
                    log("${candidate.ip} 连续两次达到 $expectedMbps Mbps，提前结束")
                    break
                }
            }
        }
        if (checkNetwork()) return@coroutineScope FamilyResult(emptyList(), emptyList(), emptyMap(), invalid = true)

        if (earlyWinner == null) {
            val confirmationCandidates = speedCandidates
                .filter { candidate ->
                    val current = samples[candidate].orEmpty()
                    current.firstOrNull()?.ok == true && !(current.size >= 2 && current.any { !it.ok })
                }
                .sortedWith(
                    compareByDescending<Candidate> { samples[it]?.firstOrNull()?.completeTransferMbps ?: 0.0 }
                        .thenBy { it.ip }
                )
                .take(params.finalLimit)
            onStage(Stage("最快候选复测", 0, confirmationCandidates.size))
            confirmationCandidates.forEachIndexed { index, candidate ->
                if (samples[candidate].orEmpty().size < params.fullRounds) {
                    samples.getOrPut(candidate) { mutableListOf() }.add(sample(candidate))
                }
                onStage(Stage("最快候选复测", index + 1, confirmationCandidates.size))
            }
        }
        if (checkNetwork()) return@coroutineScope FamilyResult(emptyList(), emptyList(), emptyMap(), invalid = true)

        val metrics = deduped.map { candidate ->
            val candidateSamples = samples[candidate].orEmpty()
            metric(
                candidate,
                routeResults[candidate],
                routeValidationRequired,
                preResults[candidate],
                candidateSamples.firstOrNull(),
                candidateSamples
            )
        }
        val pops = LinkedHashMap<String, Int>()
        metrics.map { it.primaryPop }.filter { it.isNotBlank() }
            .forEach { pop -> pops[pop.uppercase()] = (pops[pop.uppercase()] ?: 0) + 1 }
        val ranked = if (params.earlyStop) rank(metrics) else rankMaximum(metrics)
        FamilyResult(ranked, if (asiaHunt) rankAsia(metrics) else ranked, pops)
    }

    private fun chooseForSpeed(
        candidates: List<Candidate>,
        pre: Map<Candidate, ProbeEngine.ProbeResult>,
        limit: Int
    ): List<Candidate> = candidates.sortedWith(
        compareBy<Candidate> {
            pre[it]?.ttfbMs?.takeIf { value -> value >= 0.0 } ?: Double.MAX_VALUE
        }.thenBy {
            pre[it]?.tcpMs?.takeIf { value -> value >= 0.0 } ?: Double.MAX_VALUE
        }.thenBy { it.ip }
    ).take(limit)

    private fun failed(ip: String, message: String) = ProbeEngine.ProbeResult(ok = false, targetIp = ip, error = message)

    private fun metric(
        candidate: Candidate,
        route: ProbeEngine.ArgoRouteResult?,
        routeValidationRequired: Boolean,
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
        val pops = buildList {
            route?.takeIf { it.ok }?.colo?.uppercase()?.takeIf { it.isNotBlank() }?.let { add(it) }
            pre?.takeIf { it.ok }?.colo?.uppercase()?.takeIf { it.isNotBlank() }?.let { add(it) }
            full.filter { it.ok }.map { it.colo.uppercase() }.filter { it.isNotBlank() }.forEach { add(it) }
        }
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
            routeValidationRequired = routeValidationRequired,
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
        compareByDescending<IpMetric> { it.isNodeUsable }
            .thenByDescending { !it.routeValidationRequired || it.route?.ok == true }
            .thenByDescending { it.floorMbps }
            .thenByDescending { it.fullSuccessRatePct }
            .thenByDescending { it.minCompleteMbps }
            .thenByDescending { it.avgCompleteMbps }
            .thenBy { it.variationPct }
            .thenBy { if (it.medianTtfbMs < 0.0) Double.MAX_VALUE else it.medianTtfbMs }
            .thenBy { it.ip }
    )

    /** 最大带宽按两次成功样本的平均下载速度排序，再看峰值与可靠下限。 */
    fun rankMaximum(metrics: List<IpMetric>): List<IpMetric> = metrics.sortedWith(
        compareByDescending<IpMetric> { it.isNodeUsable }
            .thenByDescending { !it.routeValidationRequired || it.route?.ok == true }
            .thenByDescending { it.avgCompleteMbps }
            .thenByDescending { it.maxCompleteMbps }
            .thenByDescending { it.floorMbps }
            .thenByDescending { it.fullSuccessRatePct }
            .thenBy { it.variationPct }
            .thenBy { if (it.medianTtfbMs < 0.0) Double.MAX_VALUE else it.medianTtfbMs }
            .thenBy { it.ip }
    )

    fun rankAsia(metrics: List<IpMetric>): List<IpMetric> = metrics.sortedWith(
        compareByDescending<IpMetric> { it.isNodeUsable }
            .thenByDescending { !it.routeValidationRequired || it.route?.ok == true }
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

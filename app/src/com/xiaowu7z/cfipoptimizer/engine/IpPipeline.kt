package com.xiaowu7z.cfipoptimizer.engine

import com.xiaowu7z.cfipoptimizer.IpSources
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
    val edgeScore: Int = 0,
    val referenceVerified: Boolean = false,
    val peakKbps: Int = 0,
    val referenceLatencyMs: Int = 0,
    val dataCenter: String = "",
    val scanRound: Int = 0,
    val useTls: Boolean = true,
    val speedHost: String = ProbeEngine.SPEED_HOST
) {
    // 至少两次独立的 5 秒真实下载复测都成功，才允许复制到节点。
    val isNodeUsable: Boolean get() =
        if (referenceVerified) {
            (!routeValidationRequired || route?.ok == true) && full.isNotEmpty() && full.all { it.ok }
        } else {
            (!routeValidationRequired || route?.ok == true) &&
                full.size >= 2 && full.all { it.ok } && fullSuccessRatePct >= 99.9
        }
    // DNS 写入影响范围更大：旧流程仍要求两次复测；快速优选只接受
    // 已通过参考流程全部门禁并达到目标的单一结果。
    val isDnsSyncEligible: Boolean get() =
        isNodeUsable && (referenceVerified || full.size >= 2) && floorMbps > 0.0
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
    private const val PRE_TIMEOUT_SECONDS = 1
    private const val SPEED_TIMEOUT_SECONDS = 10
    const val QUICK_SAMPLE_MILLIS = 1_000L
    const val FULL_SAMPLE_MILLIS = 5_000L
    const val FULL_SAMPLE_SEGMENTS = 5

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
        preBytes = 0L,
        microBytes = 0L,
        fullBytes = 0L,
        microLimit = 20,
        finalLimit = 2,
        fullRounds = 2,
        preConcurrency = 32,
        microConcurrency = 1,
        fullConcurrency = 1,
        earlyStop = true
    )

    val ASIA_HUNT = ModeParams(
        preBytes = 0L,
        microBytes = 0L,
        fullBytes = 0L,
        microLimit = 20,
        finalLimit = 3,
        fullRounds = 2,
        preConcurrency = 32,
        microConcurrency = 1,
        fullConcurrency = 1,
        earlyStop = true
    )

    val MAX_BANDWIDTH = ModeParams(
        preBytes = 0L,
        microBytes = 0L,
        fullBytes = 0L,
        microLimit = 20,
        finalLimit = 3,
        fullRounds = 2,
        preConcurrency = 32,
        microConcurrency = 1,
        fullConcurrency = 1,
        earlyStop = false
    )

    /** 兼容旧调用；目标带宽现在控制快筛与 5 秒复测的响应容量。 */
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

    data class TrafficEstimate(
        val plannedMb: Double,
        val earlyStopMb: Double? = null
    )

    /**
     * 按用户填写的目标带宽估算常规计划流量，而不是把 HTTP 响应容量
     * 误当成必然完整下载。计划值包含全部 1 秒快筛与规定数量的两轮
     * 5 秒复测；早停值表示首个快筛候选即达标并通过两轮复测的情况。
     * 实际值仍会随真实线路速度、失败时机和补位次数变化。
     */
    fun estimateTraffic(
        candidateCount: Int,
        params: ModeParams,
        expectedMbps: Int = 100
    ): TrafficEstimate {
        if (candidateCount <= 0) return TrafficEstimate(0.0, if (params.earlyStop) 0.0 else null)
        val shortlist = minOf(candidateCount, params.microLimit)
        val finalists = minOf(shortlist, params.finalLimit)
        val bytesPerSecondAtTarget = expectedMbps.coerceIn(1, 2_000) * 125_000.0
        val quickBytes = shortlist * bytesPerSecondAtTarget * QUICK_SAMPLE_MILLIS / 1_000.0
        val fullBytes = finalists * params.fullRounds *
            bytesPerSecondAtTarget * FULL_SAMPLE_MILLIS / 1_000.0
        val planned = (quickBytes + fullBytes) / 1_000_000.0
        val early = if (params.earlyStop) {
            (bytesPerSecondAtTarget * QUICK_SAMPLE_MILLIS / 1_000.0 +
                params.fullRounds * bytesPerSecondAtTarget * FULL_SAMPLE_MILLIS / 1_000.0) / 1_000_000.0
        } else null
        return TrafficEstimate(plannedMb = planned, earlyStopMb = early)
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
                try { IpSources.isPublicAddress(InetAddress.getByName(candidate.ip)) } catch (_: Exception) { false }
            }
            .distinctBy { it.ip }
            .take(familyLimit)
        if (deduped.isEmpty()) return@coroutineScope FamilyResult(emptyList(), emptyList(), emptyMap())

        fun checkNetwork(): Boolean = networkInvalid().also {
            if (it) log("网络环境已变化，本轮结果作废")
        }

        val routeValidationRequired = argoHost.isNotBlank()
        val routeResults = LinkedHashMap<Candidate, ProbeEngine.ArgoRouteResult>()

        // The cheap funnel is deliberately TCP-only. The default pool is
        // bounded to official Cloudflare CIDRs; user imports may be any safe
        // public literal. Strict TLS, SNI, peer and CF-RAY validation remains
        // mandatory for every result that can be copied to a node.
        onStage(Stage("并发 TCP 三次快筛", 0, deduped.size))
        val preDone = AtomicInteger(0)
        val preResults = parallel(deduped, params.preConcurrency) { candidate ->
            coroutineContext.ensureActive()
            if (checkNetwork()) {
                candidate to failed(candidate.ip, "网络变化")
            } else {
                val result = ProbeEngine.probeTcpRtt(
                    targetIp = candidate.ip,
                    attempts = 3,
                    timeoutMillis = PRE_TIMEOUT_SECONDS * 1_000,
                    targetPort = 443,
                    log = log
                )
                onStage(Stage("并发 TCP 三次快筛", preDone.incrementAndGet(), deduped.size))
                candidate to result
            }
        }.toMap()
        if (checkNetwork()) return@coroutineScope FamilyResult(emptyList(), emptyList(), emptyMap(), invalid = true)

        val preEligible = deduped.filter { preResults[it]?.ok == true }
        if (preEligible.isEmpty()) {
            val failures = deduped.map { metric(it, routeResults[it], routeValidationRequired, preResults[it], null, emptyList()) }
            return@coroutineScope FamilyResult(rank(failures), rankAsia(failures), emptyMap())
        }

        val orderedForSpeed = orderForSpeedCandidates(
            candidates = preEligible,
            pre = preResults,
            shortlistLimit = params.microLimit,
            // A pure lowest-RTT cutoff can be monopolised by one Cloudflare
            // range whose edge accepts TCP but cannot sustain the strict
            // speed.cloudflare.com download on the current route.  Every
            // strategy therefore keeps a low-latency majority plus candidates
            // from the rest of the latency distribution.  Balanced/Asia still
            // save time and traffic through their confirmed-target early exit.
            diversifyAcrossLatency = true
        )
        val speedCandidates = if (!routeValidationRequired) {
            orderedForSpeed.take(params.microLimit)
        } else {
            // Validate only the shortlist.  A failed SNI/Host/WS candidate is
            // replaced by the next pre-screened candidate instead of shrinking
            // the useful speed-test set.
            val passed = ArrayList<Candidate>(params.microLimit)
            var cursor = 0
            var attempted = 0
            onStage(Stage("高级节点兼容复核与补位", 0, orderedForSpeed.size))
            while (passed.size < params.microLimit && cursor < orderedForSpeed.size) {
                coroutineContext.ensureActive()
                val needed = params.microLimit - passed.size
                val batchSize = minOf(needed, minOf(params.preConcurrency, 16), orderedForSpeed.size - cursor)
                val batch = orderedForSpeed.subList(cursor, cursor + batchSize)
                cursor += batchSize
                val checked = parallel(batch, minOf(batchSize, 16)) { candidate ->
                    coroutineContext.ensureActive()
                    val route = if (checkNetwork()) {
                        ProbeEngine.ArgoRouteResult(ok = false, targetIp = candidate.ip, error = "网络变化")
                    } else {
                        ProbeEngine.probeArgoRoute(
                            targetIp = candidate.ip,
                            argoHost = argoHost,
                            wsPath = wsPath,
                            targetPort = argoPort,
                            timeoutSec = ROUTE_TIMEOUT_SECONDS,
                            log = log
                        )
                    }
                    candidate to route
                }
                checked.forEach { (candidate, route) ->
                    routeResults[candidate] = route
                    attempted++
                    onStage(Stage("高级节点兼容复核与补位", attempted, orderedForSpeed.size))
                }
                passed.clear()
                passed.addAll(routeValidatedCandidates(orderedForSpeed.take(cursor), routeResults, params.microLimit))
                if (checkNetwork()) return@coroutineScope FamilyResult(emptyList(), emptyList(), emptyMap(), invalid = true)
            }
            passed.take(params.microLimit)
        }
        if (speedCandidates.isEmpty()) {
            val failures = deduped.map { metric(it, routeResults[it], routeValidationRequired, preResults[it], null, emptyList()) }
            return@coroutineScope FamilyResult(rank(failures), rankAsia(failures), emptyMap())
        }
        val quickRequestBytes = ProbeEngine.speedRequestBytes(
            expectedMbps,
            maximum = !params.earlyStop,
            sampleMillis = QUICK_SAMPLE_MILLIS
        )
        val microResults = LinkedHashMap<Candidate, ProbeEngine.ProbeResult>()
        val fullSamples = LinkedHashMap<Candidate, MutableList<ProbeEngine.ProbeResult>>()

        suspend fun sample(
            candidate: Candidate,
            requestedBytes: Long,
            sampleMillis: Long
        ): ProbeEngine.ProbeResult {
            coroutineContext.ensureActive()
            if (checkNetwork()) return failed(candidate.ip, "网络变化")
            return ProbeEngine.probeSpeedWindow(
                targetIp = candidate.ip,
                requestedBytes = requestedBytes,
                sampleMillis = sampleMillis,
                timeoutSec = SPEED_TIMEOUT_SECONDS,
                testHost = ProbeEngine.SPEED_HOST,
                targetPort = 443,
                log = log
            )
        }

        suspend fun quickSample(candidate: Candidate) =
            sample(candidate, quickRequestBytes, QUICK_SAMPLE_MILLIS)

        suspend fun fullSample(candidate: Candidate): ProbeEngine.ProbeResult {
            coroutineContext.ensureActive()
            if (checkNetwork()) return failed(candidate.ip, "网络变化")
            return ProbeEngine.probeSpeedSeries(
                targetIp = candidate.ip,
                requestedBytesPerSegment = quickRequestBytes,
                segmentCount = FULL_SAMPLE_SEGMENTS,
                segmentMillis = QUICK_SAMPLE_MILLIS,
                timeoutSec = SPEED_TIMEOUT_SECONDS,
                testHost = ProbeEngine.SPEED_HOST,
                targetPort = 443,
                log = log
            )
        }

        var earlyWinner: Candidate? = null
        onStage(Stage("1 秒候选快筛", 0, speedCandidates.size))
        for ((index, candidate) in speedCandidates.withIndex()) {
            val first = quickSample(candidate)
            microResults[candidate] = first
            onStage(Stage("1 秒候选快筛", index + 1, speedCandidates.size))
            if (checkNetwork()) return@coroutineScope FamilyResult(emptyList(), emptyList(), emptyMap(), invalid = true)

            if (params.earlyStop && first.ok && first.completeTransferMbps >= expectedMbps) {
                val current = fullSamples.getOrPut(candidate) { mutableListOf() }
                onStage(Stage("达标候选 5 秒复测 ${current.size}/${params.fullRounds}", index + 1, speedCandidates.size))
                while (current.size < params.fullRounds && current.all { it.ok }) {
                    current.add(fullSample(candidate))
                    onStage(Stage("达标候选 5 秒复测 ${current.size}/${params.fullRounds}", index + 1, speedCandidates.size))
                }
                if (hasConfirmedTarget(current, expectedMbps, params.fullRounds)) {
                    earlyWinner = candidate
                    log("${candidate.ip} 两轮 5 秒复测均达到 $expectedMbps Mbps，提前结束")
                    break
                }
            }
        }
        if (checkNetwork()) return@coroutineScope FamilyResult(emptyList(), emptyList(), emptyMap(), invalid = true)

        if (earlyWinner == null) {
            val confirmationCandidates = speedCandidates
                .filter { candidate ->
                    microResults[candidate]?.ok == true
                }
                .sortedWith(
                    compareByDescending<Candidate> { microResults[it]?.completeTransferMbps ?: 0.0 }
                        .thenBy { it.ip }
                )
            val worstCaseRequests = confirmationRequestUpperBound(
                confirmationCandidates,
                fullSamples,
                params.fullRounds
            )
            onStage(Stage("最快候选 5 秒复测与补位", 0, worstCaseRequests))
            confirmFastestCandidates(
                orderedCandidates = confirmationCandidates,
                samples = fullSamples,
                finalLimit = params.finalLimit,
                fullRounds = params.fullRounds,
                onAttempt = { current, total -> onStage(Stage("最快候选 5 秒复测与补位", current, total)) },
                sample = { fullSample(it) }
            )
        }
        if (checkNetwork()) return@coroutineScope FamilyResult(emptyList(), emptyList(), emptyMap(), invalid = true)

        val metrics = deduped.map { candidate ->
            metric(
                candidate,
                routeResults[candidate],
                routeValidationRequired,
                preResults[candidate],
                microResults[candidate],
                fullSamples[candidate].orEmpty()
            )
        }
        val pops = LinkedHashMap<String, Int>()
        metrics.map { it.primaryPop }.filter { it.isNotBlank() }
            .forEach { pop -> pops[pop.uppercase()] = (pops[pop.uppercase()] ?: 0) + 1 }
        val ranked = if (params.earlyStop) rank(metrics) else rankMaximum(metrics)
        FamilyResult(ranked, if (asiaHunt) rankAsia(metrics) else ranked, pops)
    }

    /**
     * Full candidate ordering used by the expensive stage.
     *
     * Every mode keeps a 65% low-RTT majority but reserves the rest of its
     * shortlist for evenly spaced latency quantiles.  This prevents one
     * low-RTT-but-download-incompatible range from occupying the whole strict
     * test stage, and also prevents a fast-throughput route with moderately
     * higher RTT from being excluded before it receives a real sample.
     */
    fun orderForSpeedCandidates(
        candidates: List<Candidate>,
        pre: Map<Candidate, ProbeEngine.ProbeResult>,
        shortlistLimit: Int,
        diversifyAcrossLatency: Boolean
    ): List<Candidate> {
        val latencySorted = candidates.sortedWith(compareBy<Candidate> {
            pre[it]?.ttfbMs?.takeIf { value -> value >= 0.0 } ?: Double.MAX_VALUE
        }.thenBy {
            pre[it]?.tcpMs?.takeIf { value -> value >= 0.0 } ?: Double.MAX_VALUE
        }.thenBy { it.ip })
        if (!diversifyAcrossLatency || shortlistLimit <= 2 || latencySorted.size <= shortlistLimit) {
            return latencySorted
        }

        val boundedLimit = shortlistLimit.coerceAtMost(latencySorted.size)
        val lowLatencyCount = maxOf(1, (boundedLimit * 65 + 99) / 100)
        val selected = LinkedHashSet<Candidate>()
        selected.addAll(latencySorted.take(lowLatencyCount))
        val diversitySlots = boundedLimit - selected.size
        repeat(diversitySlots) { index ->
            val tailLast = latencySorted.lastIndex
            val position = if (diversitySlots == 1) tailLast else {
                lowLatencyCount +
                    (index.toLong() * (tailLast - lowLatencyCount) / (diversitySlots - 1L)).toInt()
            }
            selected.add(latencySorted[position])
        }
        latencySorted.forEach { if (selected.size < boundedLimit) selected.add(it) }
        return selected.toList() + latencySorted.filterNot { it in selected }
    }

    fun routeValidatedCandidates(
        orderedCandidates: List<Candidate>,
        routeResults: Map<Candidate, ProbeEngine.ArgoRouteResult>,
        limit: Int
    ): List<Candidate> = orderedCandidates.asSequence()
        .filter { routeResults[it]?.ok == true }
        .take(limit.coerceAtLeast(0))
        .toList()

    /** Only two (or [fullRounds]) successful 5-second downloads make a candidate confirmed. */
    fun hasConfirmedTarget(
        samples: List<ProbeEngine.ProbeResult>,
        expectedMbps: Int,
        fullRounds: Int = 2
    ): Boolean = samples.size >= fullRounds &&
        samples.take(fullRounds).all { it.ok && it.completeTransferMbps >= expectedMbps }

    data class ConfirmationOutcome(
        val confirmed: List<Candidate>,
        val requestsMade: Int,
        val requestUpperBound: Int
    )

    fun confirmationRequestUpperBound(
        orderedCandidates: List<Candidate>,
        samples: Map<Candidate, List<ProbeEngine.ProbeResult>>,
        fullRounds: Int
    ): Int = orderedCandidates.sumOf { candidate ->
        val current = samples[candidate].orEmpty()
        if (current.any { !it.ok }) 0
        else (fullRounds - current.size).coerceAtLeast(0)
    }

    /**
     * Confirms candidates in the externally supplied one-second-funnel order.
     * [samples] contains only five-second confirmation rounds and may initially
     * be empty. A failed confirmation never consumes one of [finalLimit]; the
     * next candidate is tried until the requested number of fully successful
     * results is available or the list is exhausted.
     */
    suspend fun confirmFastestCandidates(
        orderedCandidates: List<Candidate>,
        samples: MutableMap<Candidate, MutableList<ProbeEngine.ProbeResult>>,
        finalLimit: Int,
        fullRounds: Int = 2,
        onAttempt: (current: Int, total: Int) -> Unit = { _, _ -> },
        sample: suspend (Candidate) -> ProbeEngine.ProbeResult
    ): ConfirmationOutcome {
        val upperBound = confirmationRequestUpperBound(orderedCandidates, samples, fullRounds)
        val confirmed = ArrayList<Candidate>(finalLimit.coerceAtLeast(0))
        var attempts = 0
        for (candidate in orderedCandidates) {
            coroutineContext.ensureActive()
            if (confirmed.size >= finalLimit) break
            val current = samples.getOrPut(candidate) { mutableListOf() }
            if (current.any { !it.ok }) continue
            while (current.size < fullRounds && current.all { it.ok }) {
                current.add(sample(candidate))
                attempts++
                onAttempt(attempts, upperBound)
            }
            if (current.size >= fullRounds && current.take(fullRounds).all { it.ok }) {
                confirmed.add(candidate)
            }
        }
        return ConfirmationOutcome(confirmed, attempts, upperBound)
    }

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

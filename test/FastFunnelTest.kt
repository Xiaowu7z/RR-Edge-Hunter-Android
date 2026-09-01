import com.xiaowu7z.cfipoptimizer.engine.IpPipeline
import com.xiaowu7z.cfipoptimizer.engine.ProbeEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    var passed = 0
    var failed = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) { passed++; println("PASS  $name") } else { failed++; println("FAIL  $name  $detail") }
    }

    fun probe(ok: Boolean, speed: Double, rtt: Double = 10.0) = ProbeEngine.ProbeResult(
        ok = ok,
        completeTransferMbps = if (ok) speed else 0.0,
        tcpMs = rtt,
        ttfbMs = rtt
    )

    check("TCP快筛必须完成三次", ProbeEngine.acceptedTcpAverageMs(listOf(10.0, 12.0)) == null)
    check("TCP快筛按三次平均RTT", ProbeEngine.acceptedTcpAverageMs(listOf(9.0, 12.0, 15.0)) == 12.0)

    val candidates = (0 until 30).map { index ->
        IpPipeline.Candidate("104.16.0.$index", "test")
    }
    val pre = candidates.associateWith { candidate ->
        val index = candidate.ip.substringAfterLast('.').toDouble()
        probe(ok = true, speed = 0.0, rtt = index + 1.0)
    }
    val maximumOrder = IpPipeline.orderForSpeedCandidates(
        candidates,
        pre,
        shortlistLimit = 20,
        diversifyAcrossLatency = true
    )
    check("最大带宽保留20个候选", maximumOrder.take(20).size == 20)
    check(
        "最大带宽包含延迟前20之外的跨分位候选",
        candidates.last() in maximumOrder.take(20),
        maximumOrder.take(20).joinToString { it.ip }
    )
    val normalOrder = IpPipeline.orderForSpeedCandidates(candidates, pre, 20, true)
    check("均衡与亚洲模式同样保留20个候选", normalOrder.take(20).size == 20)
    check(
        "均衡与亚洲模式不会被最低RTT单一簇垄断",
        candidates.last() in normalOrder.take(20),
        normalOrder.take(20).joinToString { it.ip }
    )

    val routeResults = mapOf(
        candidates[0] to ProbeEngine.ArgoRouteResult(ok = false, targetIp = candidates[0].ip),
        candidates[1] to ProbeEngine.ArgoRouteResult(ok = true, targetIp = candidates[1].ip),
        candidates[2] to ProbeEngine.ArgoRouteResult(ok = true, targetIp = candidates[2].ip)
    )
    check(
        "Argo失败候选由下一候选补位",
        IpPipeline.routeValidatedCandidates(candidates.take(3), routeResults, 2) == candidates.slice(1..2)
    )

    val fastest = candidates.take(3)
    val samples = linkedMapOf(
        fastest[0] to mutableListOf(probe(true, 300.0)),
        fastest[1] to mutableListOf(probe(true, 200.0)),
        fastest[2] to mutableListOf(probe(true, 100.0))
    )
    val outcome = IpPipeline.confirmFastestCandidates(
        orderedCandidates = fastest,
        samples = samples,
        finalLimit = 2,
        fullRounds = 2,
        sample = { candidate ->
            if (candidate == fastest[0]) probe(false, 0.0) else probe(true, samples.getValue(candidate).first().completeTransferMbps)
        }
    )
    check("复测失败不占finalLimit并向下补位", outcome.confirmed == fastest.drop(1), outcome.toString())
    check("补位阶段最坏请求数不低估", outcome.requestUpperBound == 3 && outcome.requestsMade == 3, outcome.toString())

    val emptyFullSamples = linkedMapOf<IpPipeline.Candidate, MutableList<ProbeEngine.ProbeResult>>()
    val fiveSecondOutcome = IpPipeline.confirmFastestCandidates(
        orderedCandidates = fastest.take(1),
        samples = emptyFullSamples,
        finalLimit = 1,
        fullRounds = 2,
        sample = { probe(true, 150.0) }
    )
    check(
        "1秒快筛不混入最终样本且另做两轮复测",
        fiveSecondOutcome.confirmed == fastest.take(1) && fiveSecondOutcome.requestsMade == 2 &&
            emptyFullSamples.getValue(fastest.first()).size == 2,
        fiveSecondOutcome.toString()
    )

    check(
        "达标候选二测失败不能提前结束",
        !IpPipeline.hasConfirmedTarget(listOf(probe(true, 150.0), probe(false, 0.0)), 100)
    )
    check(
        "达标候选二测未达目标不能提前结束",
        !IpPipeline.hasConfirmedTarget(listOf(probe(true, 150.0), probe(true, 90.0)), 100)
    )
    check(
        "两轮5秒复测达标才可提前结束",
        IpPipeline.hasConfirmedTarget(listOf(probe(true, 150.0), probe(true, 120.0)), 100)
    )

    val cancelCandidate = candidates.first()
    val cancelSamples = linkedMapOf(cancelCandidate to mutableListOf(probe(true, 100.0)))
    val entered = CompletableDeferred<Unit>()
    val cancellationJob = launch {
        IpPipeline.confirmFastestCandidates(
            listOf(cancelCandidate),
            cancelSamples,
            finalLimit = 1,
            sample = {
                entered.complete(Unit)
                awaitCancellation()
            }
        )
    }
    entered.await()
    cancellationJob.cancelAndJoin()
    check("补位复测协程可及时取消", cancellationJob.isCancelled)

    println("FastFunnelTest：PASS $passed / FAIL $failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}

import com.xiaowu7z.cfipoptimizer.engine.ProbeEngine
import kotlin.math.abs

fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) { passed++; println("PASS  $name") } else { failed++; println("FAIL  $name  $detail") }
    }

    fun assess(
        code: Int = 200,
        bytes: Long = 2_000_000L,
        requested: Long = 64_000_000L,
        bodyMs: Double = 900.0
    ) = ProbeEngine.assessSpeedSample(
        httpCode = code,
        handshakeOk = true,
        remoteMatches = true,
        rayColo = "HKG",
        downloadedBytes = bytes,
        requestedBytes = requested,
        bodyMs = bodyMs,
        sampleMillis = 1_000L
    )

    check("任意安全公网IP可作为受限导入候选", ProbeEngine.isSafePublicTarget("1.1.1.1"))
    check("私网IP不能进入探测", !ProbeEngine.isSafePublicTarget("192.168.1.1"))

    check("3xx不能作为测速成功", !assess(code = 302).ok)
    check(
        "TLS握手缺失不能成为可用样本",
        !ProbeEngine.assessSpeedSample(200, false, true, "HKG", 2_000_000L, 64_000_000L, 900.0, 1_000L).ok
    )
    check(
        "实际TCP对端不一致不能成为可用样本",
        !ProbeEngine.assessSpeedSample(200, true, false, "HKG", 2_000_000L, 64_000_000L, 900.0, 1_000L).ok
    )
    check(
        "缺少有效CF-RAY不能成为可用样本",
        !ProbeEngine.assessSpeedSample(200, true, true, "", 2_000_000L, 64_000_000L, 900.0, 1_000L).ok
    )
    check("32KB小错误页不能制造虚高", !assess(bytes = 32_768L, bodyMs = 20.0).ok)
    check("未完成且不足800ms的样本被拒绝", !assess(bytes = 2_000_000L, bodyMs = 799.0).ok)
    check("达到约800ms的真实窗口被接受", assess(bytes = 2_000_000L, bodyMs = 850.0).ok)
    check(
        "5秒复测不足4秒且未完整下载时被拒绝",
        !ProbeEngine.assessSpeedSample(200, true, true, "HKG", 20_000_000L, 75_000_000L, 3_999.0, 5_000L).ok
    )
    check(
        "5秒复测达到八成窗口时被接受",
        ProbeEngine.assessSpeedSample(200, true, true, "HKG", 20_000_000L, 75_000_000L, 4_100.0, 5_000L).ok
    )
    check(
        "完整预期大样本允许安全的提前完成例外",
        assess(bytes = 4_000_000L, requested = 4_000_000L, bodyMs = 300.0).ok
    )
    check(
        "高速线路150ms收完大样本仍是有效实测",
        assess(bytes = 18_750_000L, requested = 18_750_000L, bodyMs = 150.0).ok
    )
    check(
        "完整但过小的错误体不能使用提前完成例外",
        !assess(bytes = 65_536L, requested = 65_536L, bodyMs = 300.0).ok
    )

    val rates = ProbeEngine.calculateSpeedRates(
        downloadedBytes = 10_000_000L,
        bodyMs = 1_000.0,
        completeMs = 1_250.0,
        callTotalMs = 2_000.0
    )
    check("payload速率只按body时间", rates.payloadMbps == 80.0, rates.toString())
    check("complete速率包含连接时间", rates.completeTransferMbps == 64.0, rates.toString())
    check("callTotal速率包含完整调用时间", rates.callTotalMbps == 40.0, rates.toString())

    fun successfulSegment(index: Int) = ProbeEngine.ProbeResult(
        ok = true,
        family = "IPv4",
        targetIp = "104.16.0.1",
        actualRemoteAddress = "104.16.0.1",
        targetMatchesRemote = true,
        certVerified = true,
        httpCode = 200,
        httpVersion = "http/1.1",
        dnsMs = 10.0 + index,
        tcpMs = 20.0 + index,
        tlsMs = 30.0 + index,
        ttfbMs = 40.0 + index,
        bodyMs = 1_000.0,
        totalMs = 1_250.0,
        callTotalMs = 1_500.0,
        bytesDownloaded = 10_000_000L,
        bytesTarget = 64_000_000L,
        payloadMbps = 80.0,
        completeTransferMbps = 64.0,
        callTotalMbps = 53.333333,
        colo = "HKG",
        events = "sample-$index"
    )

    val fiveSegments = (1..5).map(::successfulSegment)
    val combined = ProbeEngine.combineSpeedSegments(fiveSegments, "104.16.0.1")
    check("五个一秒成功段可合成一轮累计五秒复测", combined.ok, combined.toString())
    check(
        "累计五秒按总字节和总完整传输时间计算速度",
        combined.bytesDownloaded == 50_000_000L && combined.bodyMs == 5_000.0 &&
            combined.totalMs == 6_250.0 && abs(combined.completeTransferMbps - 64.0) < 0.0001,
        combined.toString()
    )
    check(
        "累计五秒保留严格TLS与真实对端证据",
        combined.certVerified && combined.targetMatchesRemote && combined.colo == "HKG",
        combined.toString()
    )

    val failedSecond = successfulSegment(2).copy(ok = false, error = "SocketTimeoutException")
    val failedSeries = ProbeEngine.combineSpeedSegments(
        listOf(successfulSegment(1), failedSecond),
        "104.16.0.1"
    )
    check(
        "任一下载段失败会让整轮失败并标明段号",
        !failedSeries.ok && failedSeries.completeTransferMbps == 0.0 &&
            failedSeries.error.contains("第 2/5 段失败") && failedSeries.error.contains("SocketTimeoutException"),
        failedSeries.toString()
    )
    val incompleteSeries = ProbeEngine.combineSpeedSegments(fiveSegments.take(4), "104.16.0.1")
    check(
        "不足五段不能冒充累计五秒结果",
        !incompleteSeries.ok && incompleteSeries.error.contains("仅完成 4/5 段"),
        incompleteSeries.toString()
    )

    println("SpeedWindowPolicyTest：PASS $passed / FAIL $failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}

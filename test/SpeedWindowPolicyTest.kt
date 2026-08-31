import com.xiaowu7z.cfipoptimizer.engine.ProbeEngine

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

    println("SpeedWindowPolicyTest：PASS $passed / FAIL $failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}

import com.xiaowu7z.cfipoptimizer.engine.IpMetric
import com.xiaowu7z.cfipoptimizer.engine.IpPipeline
import com.xiaowu7z.cfipoptimizer.engine.ProbeEngine

fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) { passed++; println("PASS  $name") } else { failed++; println("FAIL  $name  $detail") }
    }

    fun metric(ip: String, pop: String, floor: Double, avg: Double, success: Double = 100.0, variation: Double = 5.0) =
        IpMetric(
            ip = ip,
            family = "IPv4",
            source = "test",
            floorMbps = floor,
            minCompleteMbps = floor,
            avgCompleteMbps = avg,
            fullSuccessRatePct = success,
            variationPct = variation,
            primaryPop = pop,
            edgeScore = IpPipeline.popPriority(pop) * 10 + 1,
            full = List(3) { ProbeEngine.ProbeResult(ok = true, targetIp = ip) }
        )

    val direct = metric("104.16.0.10", "HKG", floor = 50.0, avg = 60.0)
    check("默认直接IP模式不需要Argo域名验证", direct.route == null && direct.isNodeUsable)
    check(
        "启用高级复核后必须有通过的路由结果",
        !direct.copy(routeValidationRequired = true).isNodeUsable
    )
    check("完整三轮全成功才允许作为 DNS 冠军", direct.isDnsSyncEligible)
    check(
        "不足固定三轮不得作为 DNS 冠军",
        !direct.copy(full = direct.full.take(2)).isDnsSyncEligible
    )
    check(
        "任一 Full 轮失败不得作为 DNS 冠军",
        !direct.copy(
            full = direct.full.dropLast(1) + ProbeEngine.ProbeResult(ok = false, targetIp = direct.ip)
        ).isDnsSyncEligible
    )
    check(
        "可靠下限为零不得作为 DNS 冠军",
        !direct.copy(floorMbps = 0.0).isDnsSyncEligible
    )
    check(
        "含失败轮次的可复制结果不得触发 DNS 写入",
        !direct.copy(
            fullSuccessRatePct = 66.7,
            floorMbps = 0.0,
            full = listOf(
                ProbeEngine.ProbeResult(ok = true, targetIp = direct.ip),
                ProbeEngine.ProbeResult(ok = true, targetIp = direct.ip),
                ProbeEngine.ProbeResult(ok = false, targetIp = direct.ip)
            )
        ).isDnsSyncEligible
    )

    val target50 = IpPipeline.forExpectedMbps(IpPipeline.ASIA_HUNT, 50)
    val target100 = IpPipeline.forExpectedMbps(IpPipeline.ASIA_HUNT, 100)
    val target500 = IpPipeline.forExpectedMbps(IpPipeline.ASIA_HUNT, 500)
    check(
        "期望带宽实际调整Full样本大小",
        target50.fullBytes < target100.fullBytes && target100.fullBytes < target500.fullBytes,
        "${target50.fullBytes}/${target100.fullBytes}/${target500.fullBytes}"
    )

    val slowHkg = metric("104.16.0.1", "HKG", floor = 1.0, avg = 1.2)
    val fastOther = metric("104.16.0.2", "LAX", floor = 80.0, avg = 100.0)
    val asia = IpPipeline.rankAsia(listOf(slowHkg, fastOther))
    check("亚洲狩猎不允许 POP 压过巨大速度差", asia.firstOrNull()?.ip == fastOther.ip, asia.toString())

    val unstableHkg = metric("104.16.0.3", "HKG", floor = 0.0, avg = 120.0, success = 66.7, variation = 80.0)
    val stableNrt = metric("104.16.0.4", "NRT", floor = 35.0, avg = 40.0, success = 100.0, variation = 8.0)
    check(
        "视频稳定性优先于瞬时均速",
        IpPipeline.rankAsia(listOf(unstableHkg, stableNrt)).firstOrNull()?.ip == stableNrt.ip
    )

    val tieHkg = metric("104.16.0.5", "HKG", floor = 50.0, avg = 60.0)
    val tieNrt = metric("104.16.0.6", "NRT", floor = 50.0, avg = 60.0)
    check("同档成绩才使用亚洲 POP 加分", IpPipeline.rankAsia(listOf(tieNrt, tieHkg)).firstOrNull()?.ip == tieHkg.ip)

    println("IpPipelineRankTest：PASS $passed / FAIL $failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}

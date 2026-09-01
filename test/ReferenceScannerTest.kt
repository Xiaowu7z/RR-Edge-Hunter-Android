import com.xiaowu7z.cfipoptimizer.engine.MaintainedPool
import com.xiaowu7z.cfipoptimizer.engine.ReferenceScanner
import java.util.Random

fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) { passed++; println("PASS  $name") } else { failed++; println("FAIL  $name  $detail") }
    }

    val parsed = MaintainedPool.parseRanges(
        "104.16.0.1/24\n104.16.0.0/24\n10.0.0.0/8\nbad",
        "IPv4"
    )
    check("维护网段会规范化、去重并拒绝私网", parsed == listOf("104.16.0.0/24"), parsed.toString())

    val target = MaintainedPool.parseSpeedTarget("https://speed.cloudflare.com/__down?bytes=1000")
    check("动态测速地址拆分域名与路径", target == Pair("speed.cloudflare.com", "/__down?bytes=1000"), target.toString())

    val ranges = (16 until 116).map { "104.$it.0.0/16" }
    val round = ReferenceScanner.buildRoundCandidates(
        ranges,
        listOf("1.1.1.1", "2606:4700::1111", "192.168.1.1"),
        "IPv4",
        Random(7)
    )
    check("每轮固定生成100个候选", round.size == 100, round.size.toString())
    check("用户公网IPv4保留在本轮", round.any { it.ip == "1.1.1.1" && it.source == "我的 IP 名单" })
    check("跨族与私网导入不会进入本轮", round.none { it.ip == "2606:4700::1111" || it.ip == "192.168.1.1" })
    check("本轮候选不重复", round.map { it.ip }.toSet().size == round.size)

    println("ReferenceScannerTest：PASS $passed / FAIL $failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}

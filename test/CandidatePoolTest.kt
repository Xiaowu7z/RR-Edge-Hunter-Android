import com.xiaowu7z.cfipoptimizer.engine.AuthorizedHostSnapshot
import com.xiaowu7z.cfipoptimizer.engine.CandidatePool
import com.xiaowu7z.cfipoptimizer.engine.CfRanges
import java.net.InetAddress

fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) { passed++; println("PASS  $name") } else { failed++; println("FAIL  $name  $detail") }
    }

    val v4Samples = CfRanges.sampleOfficial("IPv4", perRange = 2, limit = 30)
    check("IPv4 官方抽样受上限约束", v4Samples.size in 1..30, "size=${v4Samples.size}")
    check("IPv4 官方抽样全部属于 CF", v4Samples.all { CfRanges.isCloudflare(InetAddress.getByName(it)) })
    val v6Samples = CfRanges.sampleOfficial("IPv6", perRange = 2, limit = 14)
    check("IPv6 官方抽样受上限约束", v6Samples.size in 1..14, "size=${v6Samples.size}")
    check("IPv6 官方抽样全部属于 CF", v6Samples.all { CfRanges.isCloudflare(InetAddress.getByName(it)) })

    val snapshot = AuthorizedHostSnapshot(
        host = "argo.example.com",
        ipv4 = listOf("104.16.0.1"),
        ipv6 = listOf("2606:4700::1")
    )
    val importedOnly = CandidatePool.build(
        snapshot,
        imported = listOf("104.16.99.88", "1.1.1.1", "2606:4700::99"),
        family = "IPv4",
        includeOfficialSamples = false
    )
    check(
        "导入 CF IP 无需与当前 DNS 求交",
        importedOnly.candidates.any { it.ip == "104.16.99.88" && it.source == "用户IP池" },
        importedOnly.toString()
    )
    check("非 CF 导入地址被拒绝", importedOnly.ignoredOutsideCloudflare == 1, importedOnly.toString())
    check("其他协议族单独统计", importedOnly.ignoredWrongFamily == 1, importedOnly.toString())
    check("当前 DNS 始终作为首个种子", importedOnly.candidates.firstOrNull()?.source == "当前DNS", importedOnly.toString())

    val many = (0..255).map { "104.16.0.$it" }
    val bounded = CandidatePool.build(snapshot, many, "IPv4", includeOfficialSamples = true)
    check(
        "总候选与导入候选均受控",
        bounded.candidates.size <= CandidatePool.MAX_V4_CANDIDATES &&
            bounded.candidates.count { it.source == "用户IP池" } <= CandidatePool.MAX_IMPORTED_PER_FAMILY,
        bounded.toString()
    )
    check("长列表采用分散抽样", bounded.importedSampled, bounded.toString())

    println("CandidatePoolTest：PASS $passed / FAIL $failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}

import com.xiaowu7z.cfipoptimizer.engine.AuthorizedHost
import com.xiaowu7z.cfipoptimizer.engine.AuthorizedHostSnapshot
import com.xiaowu7z.cfipoptimizer.engine.CfRanges
import java.net.InetAddress

fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) { passed++; println("PASS  $name") } else { failed++; println("FAIL  $name  $detail") }
    }
    fun fails(name: String, block: () -> Unit) {
        try { block(); check(name, false, "未抛出异常") } catch (_: IllegalArgumentException) { check(name, true) }
    }

    check("规范化测试主机", AuthorizedHost.normalizeHost("Speed.Cloudflare.Com.") == "speed.cloudflare.com")
    fails("拒绝 IP 测试主机") { AuthorizedHost.normalizeHost("104.16.0.1") }
    fails("拒绝 URL 测试主机") { AuthorizedHost.normalizeHost("https://speed.cloudflare.com") }
    fails("拒绝带端口测试主机") { AuthorizedHost.normalizeHost("speed.cloudflare.com:443") }
    fails("拒绝四段纯数字伪主机") { AuthorizedHost.normalizeHost("001.002.003.004") }
    check("WS Path 可留空", AuthorizedHost.normalizeWsPath("  ").isEmpty())
    check("WS Path 保留查询参数", AuthorizedHost.normalizeWsPath("/vless?ed=2048") == "/vless?ed=2048")
    fails("WS Path 必须以斜杠开头") { AuthorizedHost.normalizeWsPath("vless") }
    fails("WS Path 拒绝协议相对主机") { AuthorizedHost.normalizeWsPath("//evil.example/x") }
    fails("WS Path 拒绝换行") { AuthorizedHost.normalizeWsPath("/ok\nInjected: x") }
    fails("WS Path 拒绝畸形百分号转义") { AuthorizedHost.normalizeWsPath("/vless%2") }
    fails("WS Path 百分号转义只接受 ASCII 十六进制") { AuthorizedHost.normalizeWsPath("/vless%２F") }
    check("已知 Cloudflare 网段允许", CfRanges.isCloudflare(InetAddress.getByName("104.16.0.1")))
    check("非 Cloudflare 地址不在回退网段", !CfRanges.isCloudflare(InetAddress.getByName("1.1.1.1")))

    val snapshot = AuthorizedHostSnapshot(
        host = "speed.cloudflare.com",
        ipv4 = listOf("104.16.0.1", "104.16.0.2"),
        ipv6 = listOf("2606:4700::1")
    )
    val v4 = AuthorizedHost.intersectImported(snapshot, listOf("104.16.0.2", "1.1.1.1", "2606:4700::1"), "IPv4")
    check("IPv4 只保留当前 DNS 交集", v4.candidates == listOf("104.16.0.2") && v4.ignoredOutsideCurrentDns == 1, v4.toString())
    val v6 = AuthorizedHost.intersectImported(snapshot, listOf("2606:4700:0:0:0:0:0:1", "2606:4700::2"), "IPv6")
    check("IPv6 按字节规范化后取交集", v6.candidates == listOf("2606:4700::1") && v6.intersectionCount == 1, v6.toString())
    val none = AuthorizedHost.intersectImported(snapshot, listOf("1.1.1.1"), "IPv4")
    check("快照外 IP 不得回退为候选", none.candidates.isEmpty() && none.ignoredOutsideCurrentDns == 1, none.toString())

    println("AuthorizedHostTest：PASS $passed / FAIL $failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}

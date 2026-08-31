import com.xiaowu7z.cfipoptimizer.engine.FixedDns
import java.net.UnknownHostException

fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) {
            passed++
            println("PASS  $name")
        } else {
            failed++
            println("FAIL  $name  $detail")
        }
    }

    val dns = FixedDns.forTestHost("Speed.Cloudflare.Com", "104.16.0.1")
    check("仅解析规范化后的授权主机", dns.lookup("speed.cloudflare.com").single().hostAddress == "104.16.0.1")
    check("主机名大小写不会扩大映射范围", dns.lookup("SPEED.CLOUDFLARE.COM").single().hostAddress == "104.16.0.1")
    try {
        dns.lookup("example.com")
        check("未知主机不得回退到系统 DNS", false, "未抛出异常")
    } catch (_: UnknownHostException) {
        check("未知主机不得回退到系统 DNS", true)
    }

    println("FixedDnsTest：PASS $passed / FAIL $failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}

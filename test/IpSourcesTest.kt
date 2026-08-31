import com.xiaowu7z.cfipoptimizer.IpSourceException
import com.xiaowu7z.cfipoptimizer.IpSources
import com.xiaowu7z.cfipoptimizer.IpSubscription
import com.xiaowu7z.cfipoptimizer.IpSubscriptionResolver
import java.net.InetAddress
import java.util.Base64

fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, condition: Boolean, detail: String = "") {
        if (condition) {
            passed++
            println("PASS  $name")
        } else {
            failed++
            println("FAIL  $name  $detail")
        }
    }
    fun fails(name: String, block: () -> Unit) {
        try {
            block()
            check(name, false, "未抛出异常")
        } catch (_: IpSourceException) {
            check(name, true)
        }
    }

    val plain = IpSources.parse(
        "# comment\n104.16.0.1\n104.16.0.1:443\n[2606:4700:4700::1111]:443\n104.16.1.0/30\n10.0.0.1\n"
    )
    check(
        "TXT / IPv4 / IPv6 / :443 / CIDR / 去重",
        plain.ips == listOf("104.16.0.1", "2606:4700:4700::1111", "104.16.1.1", "104.16.1.2"),
        plain.toString()
    )
    check("TXT 统计 CIDR 和无效项", plain.cidrCount == 1 && plain.ignored == 1, plain.toString())
    check("协议族统计", plain.ipv4Count == 3 && plain.ipv6Count == 1, plain.toString())
    check(
        "单个方括号 IPv6:443 不误判为 JSON",
        IpSources.parse("[2606:4700::77]:443").ips == listOf("2606:4700::77")
    )

    val port = IpSources.parse("104.16.0.2:443\n104.16.0.3:8443\n")
    check("仅接收固定探针端口 443", port.ips == listOf("104.16.0.2") && port.ignored == 1, port.toString())
    check("非 443 端口有明确警告", port.warnings.any { it.contains("443") }, port.warnings.toString())
    fails("拒绝非 443 端口") { IpSources.normalizeIp("104.16.0.3:8443") }

    val csv = IpSources.parse(
        "name,ip,address\nA,104.16.0.4,2606:4700::1111\nB,104.16.0.5,2606:4700::2222\n",
        "wrong.txt"
    )
    check(
        "按内容识别 CSV 多 IP 列",
        csv.sourceFormat == "CSV" && csv.ips == listOf("104.16.0.4", "2606:4700::1111", "104.16.0.5", "2606:4700::2222"),
        csv.toString()
    )
    val tsv = IpSources.parse("label\tipv6\nA\t2606:4700::3333\n")
    check("按内容识别 TSV", tsv.sourceFormat == "TSV" && tsv.ips == listOf("2606:4700::3333"), tsv.toString())

    val json = IpSources.parse("{\"data\":[{\"ip\":\"104.16.0.6\"},{\"addresses\":[\"2606:4700::4444\"]}]}")
    check(
        "JSON 支持嵌套 IP 字段",
        json.sourceFormat == "JSON" && json.ips == listOf("104.16.0.6", "2606:4700::4444"),
        json.toString()
    )
    fails("拒绝非标准 JSON 数字") { IpSources.parse("{\"ips\":[\"104.16.0.1\"],\"bad\":+1}") }
    val deepJson = "{\"data\":".repeat(IpSources.MAX_JSON_DEPTH + 2) + "\"104.16.0.1\"" + "}".repeat(IpSources.MAX_JSON_DEPTH + 2)
    fails("拒绝过深 JSON，避免递归耗尽") { IpSources.parse(deepJson) }

    val encoded = Base64.getEncoder().encodeToString("104.16.0.7\n2606:4700::5555\n".toByteArray())
    val base64 = IpSources.parse(encoded)
    check(
        "Base64 包裹 TXT",
        base64.sourceFormat == "Base64 + TXT" && base64.ips == listOf("104.16.0.7", "2606:4700::5555"),
        base64.toString()
    )

    check("IPv6 规范化与协议识别", IpSources.normalizeIp("2606:4700:0:0:0:0:0:1") == "2606:4700::1" &&
        IpSources.familyOf("2606:4700::1") == "IPv6")
    fails("拒绝 IP URL") { IpSources.normalizeIp("https://104.16.0.1:443/") }
    fails("拒绝 IPv6 zone") { IpSources.normalizeIp("fe80::1%wlan0") }
    fails("拒绝私网和保留地址") { IpSources.parse("127.0.0.1\n192.168.1.1\n2001:db8::1\nnot-an-ip") }
    fails("拒绝二进制来源") { IpSources.parseBytes(byteArrayOf(65, 0, 66), "ips.txt") }
    fails("拒绝非法 IPv4 CIDR 前缀") { IpSources.parse("104.16.0.0/33") }
    fails("拒绝非法 IPv6 CIDR 前缀") { IpSources.parse("2606:4700::/129") }

    val sampled = IpSources.parse("104.16.0.0/13")
    check(
        "大 CIDR 受控且确定性抽样",
        sampled.ips.size == IpSources.MAX_CIDR_SAMPLES && sampled.sampledCidrs == 1 && sampled.ips.first() == "104.16.0.1" &&
            sampled.ips.distinct().size == sampled.ips.size,
        sampled.toString()
    )

    val publicResolver = IpSubscriptionResolver { listOf(InetAddress.getByName("93.184.216.34")) }
    val validated = IpSubscription.validateUrl("https://example.com/list.txt#ignored", publicResolver)
    check("订阅仅保留安全 HTTPS URL", validated.uri.toString() == "https://example.com/list.txt", validated.uri.toString())
    val encodedUrl = IpSubscription.validateUrl("https://example.com/a%2Fb?next=%2Fedge#ignored", publicResolver)
    check(
        "订阅保留编码路径和查询",
        encodedUrl.uri.rawPath == "/a%2Fb" && encodedUrl.uri.rawQuery == "next=%2Fedge",
        encodedUrl.uri.toString()
    )
    fails("订阅拒绝 HTTP") { IpSubscription.validateUrl("http://example.com/list", publicResolver) }
    fails("订阅拒绝账号密码") { IpSubscription.validateUrl("https://user:pass@example.com/list", publicResolver) }
    fails("订阅拒绝非 443 端口") { IpSubscription.validateUrl("https://example.com:8443/list", publicResolver) }
    fails("订阅拒绝本机地址") { IpSubscription.validateUrl("https://127.0.0.1/list", publicResolver) }
    fails("订阅拒绝 metadata 域名") { IpSubscription.validateUrl("https://metadata.google.internal/list", publicResolver) }
    val privateResolver = IpSubscriptionResolver { listOf(InetAddress.getByName("127.0.0.1")) }
    fails("订阅拒绝私网 DNS 答案") { IpSubscription.validateUrl("https://example.com/list", privateResolver) }
    val mixedResolver = IpSubscriptionResolver {
        listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("169.254.169.254"))
    }
    fails("订阅拒绝混合公网与链路本地答案") { IpSubscription.validateUrl("https://example.com/list", mixedResolver) }
    var resolves = 0
    val rebindingResolver = IpSubscriptionResolver {
        resolves++
        if (resolves == 1) listOf(InetAddress.getByName("93.184.216.34")) else listOf(InetAddress.getByName("10.0.0.1"))
    }
    val rebindValidated = IpSubscription.validateUrl("https://example.com/list", rebindingResolver)
    fails("订阅连接前再次解析会拦截 rebinding") { IpSubscription.revalidateUrl(rebindValidated, rebindingResolver) }
    check("订阅重解析确实执行", resolves == 2, "calls=$resolves")
    check("CGNAT 不是公网订阅目标", !IpSubscription.isPublicAddress(InetAddress.getByName("100.64.0.1")))
    check("元数据 IP 不是公网订阅目标", !IpSubscription.isPublicAddress(InetAddress.getByName("100.100.100.200")))
    check("文档 IPv6 不是公网订阅目标", !IpSubscription.isPublicAddress(InetAddress.getByName("2001:db8::1")))
    check("IPv6 过渡前缀不是公网订阅目标", !IpSubscription.isPublicAddress(InetAddress.getByName("64:ff9b::a9fe:a9fe")))
    check("普通公网地址允许", IpSubscription.isPublicAddress(InetAddress.getByName("1.1.1.1")))

    println("IpSourcesTest：PASS $passed / FAIL $failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}

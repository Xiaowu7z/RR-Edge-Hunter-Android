import com.xiaowu7z.cfipoptimizer.engine.NodeRouteParser
import java.util.Base64

fun main() {
    fun check(name: String, condition: Boolean) {
        if (!condition) error("FAILED: $name")
        println("PASS: $name")
    }
    fun fails(name: String, block: () -> Unit) {
        try { block(); error("FAILED: $name") } catch (_: IllegalArgumentException) { println("PASS: $name") }
    }

    val json = """{"v":"2","add":"104.16.0.1","port":"2053","id":"secret-uuid","net":"ws","host":"route.trycloudflare.com","path":"/argo?ed=2048","tls":"tls","sni":"route.trycloudflare.com"}"""
    val vmess = "vmess://" + Base64.getEncoder().withoutPadding().encodeToString(json.toByteArray())
    val parsedVmess = NodeRouteParser.parse(vmess)
    check("VMess 只提取非秘密路由字段", parsedVmess.protocol == "VMess" && parsedVmess.port == 2053 && parsedVmess.sni == "route.trycloudflare.com" && parsedVmess.wsPath == "/argo?ed=2048" && !parsedVmess.toString().contains("secret"))

    val parsedVless = NodeRouteParser.parse("vless://uuid@104.17.0.1:443?type=ws&security=tls&sni=tls.example.com&host=ws.example.com&path=%2Fvless%3Fed%3D2048#demo")
    check("VLESS 保留不同 SNI 与 Host", parsedVless.protocol == "VLESS" && parsedVless.sni == "tls.example.com" && parsedVless.hostHeader == "ws.example.com" && parsedVless.wsPath == "/vless?ed=2048")

    fails("拒绝非 WS 节点") { NodeRouteParser.parse("vless://uuid@example.com:443?type=tcp&security=tls") }
    fails("拒绝 Reality 节点") { NodeRouteParser.parse("vless://uuid@example.com:443?type=ws&security=reality&host=example.com") }
    fails("拒绝不受支持的端口") { NodeRouteParser.parse("vless://uuid@example.com:1234?type=ws&security=tls&host=example.com") }
}

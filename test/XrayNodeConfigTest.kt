import com.xiaowu7z.cfipoptimizer.engine.NodeRouteTemplate
import com.xiaowu7z.cfipoptimizer.engine.XrayNodeConfig
import org.json.JSONObject

private fun checkThat(value: Boolean, message: String) {
    if (!value) throw IllegalStateException(message)
}

fun main() {
    val route = NodeRouteTemplate("VLESS", "argo.example.com", "argo.example.com", 2053, "/secret-path")
    val converted = JSONObject()
        .put("success", true)
        .put(
            "data",
            JSONObject().put(
                "outbounds",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("protocol", "vless")
                        .put(
                            "settings",
                            JSONObject()
                                .put("address", "original.example.com")
                                .put("port", 2053)
                                .put("id", "12345678-abcd-abcd-abcd-123456789abc")
                                .put("encryption", "none")
                        )
                        .put(
                            "streamSettings",
                            JSONObject()
                                .put("network", "ws")
                                .put("security", "tls")
                                .put("wsSettings", JSONObject().put("path", "/secret-path"))
                        )
                )
            )
        )
        .toString()

    val profile = XrayNodeConfig.profileFromConvertResponse(route, converted)
    checkThat(!profile.toString().contains("12345678"), "profile toString leaked UUID")
    val candidate = JSONObject(XrayNodeConfig.configForCandidate(profile, "104.18.1.2"))
    val outbound = candidate.getJSONArray("outbounds").getJSONObject(0)
    val settings = outbound.getJSONObject("settings")
    checkThat(settings.getString("address") == "104.18.1.2", "candidate address was not substituted")
    checkThat(settings.getInt("port") == 2053, "port changed")
    checkThat(settings.getString("id").startsWith("12345678"), "UUID changed")
    checkThat(outbound.getJSONObject("streamSettings").getJSONObject("wsSettings").getString("path") == "/secret-path", "WS path changed")

    val request = JSONObject(XrayNodeConfig.pingBatchRequest("/private/cache/node.json"))
    checkThat(request.getString("method") == "pingBatch", "wrong native method")
    checkThat(request.getJSONObject("payload").getString("url") == "https://www.gstatic.com/generate_204", "not aligned with V2rayNG default")
    checkThat(request.getJSONObject("payload").getInt("timeout") == 5, "wrong node timeout")

    val passed = XrayNodeConfig.pingResult("""{"success":true,"data":{"results":[{"success":true,"delay":186}]},"error":""}""")
    checkThat(passed.ok && passed.delayMs == 186L, "valid full-node delay was rejected")
    val failed = XrayNodeConfig.pingResult("""{"success":true,"data":{"results":[{"success":false,"delay":11000,"error":"timeout"}]},"error":""}""")
    checkThat(!failed.ok, "timeout became copyable")

    var rejected = false
    try {
        XrayNodeConfig.profileFromConvertResponse(route, """{"success":true,"data":{"outbounds":[]},"error":""}""")
    } catch (_: IllegalArgumentException) {
        rejected = true
    }
    checkThat(rejected, "empty outbound was accepted")
    println("XrayNodeConfigTest OK")
}

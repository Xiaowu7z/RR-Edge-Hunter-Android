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
                                .put(
                                    "tlsSettings",
                                    JSONObject().put("serverName", "tls.example.com")
                                )
                                .put(
                                    "wsSettings",
                                    JSONObject()
                                        .put("path", "/secret-path")
                                        .put(
                                            "headers",
                                            JSONObject().put("Host", "ws.example.com")
                                        )
                                )
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
    val stream = outbound.getJSONObject("streamSettings")
    checkThat(stream.getJSONObject("tlsSettings").getString("serverName") == "tls.example.com", "TLS SNI changed")
    checkThat(stream.getJSONObject("wsSettings").getString("path") == "/secret-path", "WS path changed")
    checkThat(stream.getJSONObject("wsSettings").getJSONObject("headers").getString("Host") == "ws.example.com", "WS Host changed")

    // NodeRouteTemplate contains only safe display metadata. Even when that
    // summary has a fallback Host, it must never be injected into a converted
    // outbound whose WS settings intentionally omit an explicit Host header.
    val implicitHostResponse = JSONObject(converted)
    implicitHostResponse
        .getJSONObject("data")
        .getJSONArray("outbounds")
        .getJSONObject(0)
        .getJSONObject("streamSettings")
        .getJSONObject("wsSettings")
        .remove("headers")
    val implicitHostProfile = XrayNodeConfig.profileFromConvertResponse(route, implicitHostResponse.toString())
    val implicitHostCandidate = JSONObject(XrayNodeConfig.configForCandidate(implicitHostProfile, "104.18.1.3"))
    val implicitWs = implicitHostCandidate
        .getJSONArray("outbounds")
        .getJSONObject(0)
        .getJSONObject("streamSettings")
        .getJSONObject("wsSettings")
    checkThat(!implicitWs.has("headers"), "summary Host was injected into the Xray outbound")

    val convertRequest = JSONObject(XrayNodeConfig.convertRequest("vless://redacted"))
    checkThat(convertRequest.getInt("apiVersion") == 1, "wrong pinned libXray API version")
    checkThat(convertRequest.getString("method") == "convertShareLinksToXrayJson", "wrong conversion method")

    val request = JSONObject(XrayNodeConfig.pingBatchRequest("/private/cache/node.json"))
    checkThat(request.getInt("apiVersion") == 1, "ping request is not compatible with libXray v26.7.28")
    checkThat(request.getString("method") == "pingBatch", "wrong native method")
    checkThat(request.getJSONObject("payload").getString("url") == "https://www.gstatic.com/generate_204", "not aligned with V2rayNG default")
    checkThat(request.getJSONObject("payload").getInt("timeout") == 5, "wrong node timeout")
    val pingItem = request.getJSONObject("payload").getJSONArray("configs").getJSONObject(0)
    checkThat(pingItem.getString("configPath") == "/private/cache/node.json", "pinned pingBatch config path was lost")
    checkThat(!pingItem.has("xrayJson"), "newer incompatible libXray API leaked into the pinned request")

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

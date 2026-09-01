package com.xiaowu7z.cfipoptimizer.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * An Xray configuration produced from one VMess/VLESS share link.
 *
 * [configJson] contains credentials and must remain in memory only. It is never
 * written to preferences, history, logs, crash text, or result exports.
 */
data class XrayNodeProfile(
    val route: NodeRouteTemplate,
    val configJson: String
) {
    override fun toString(): String = "XrayNodeProfile(route=$route, configJson=<redacted>)"
}

data class XrayPingResult(
    val ok: Boolean,
    val delayMs: Long = -1L,
    val error: String = ""
)

/** Pure JSON helpers kept separate from the native bridge for deterministic tests. */
object XrayNodeConfig {
    // RR Edge Hunter pins libXray v26.7.28. That exact release uses Invoke
    // API v1 and configPath for pingBatch; newer libXray main uses a different
    // in-memory v2 contract and must not be mixed with the pinned AAR.
    const val LIBXRAY_API_VERSION = 1
    const val DELAY_TEST_URL = "https://www.gstatic.com/generate_204"
    const val PING_TIMEOUT_SECONDS = 5

    fun convertRequest(shareLink: String): String = JSONObject()
        .put("apiVersion", LIBXRAY_API_VERSION)
        .put("method", "convertShareLinksToXrayJson")
        .put("payload", JSONObject().put("text", shareLink))
        .toString()

    fun profileFromConvertResponse(
        route: NodeRouteTemplate,
        responseText: String
    ): XrayNodeProfile {
        val response = parseObject(responseText, "Xray 没有返回有效响应")
        if (!response.optBoolean("success", false)) {
            throw IllegalArgumentException(safeError(response.optString("error"), "Xray 无法识别该节点"))
        }
        val config = response.optJSONObject("data")
            ?: throw IllegalArgumentException("Xray 没有生成节点配置")
        val outbound = onlyOutbound(config)
        val protocol = outbound.optString("protocol").lowercase()
        val expected = route.protocol.lowercase()
        if (protocol != expected || protocol !in setOf("vmess", "vless")) {
            throw IllegalArgumentException("节点协议与分享链接不一致")
        }
        ensureAddressSlot(outbound)
        return XrayNodeProfile(route, config.toString())
    }

    /** Replaces only the server address; every other node field is preserved. */
    fun configForCandidate(profile: XrayNodeProfile, candidateIp: String): String {
        require(ProbeEngine.isIpLiteral(candidateIp)) { "候选不是有效 IP" }
        val config = parseObject(profile.configJson, "节点配置已失效")
        val outbound = onlyOutbound(config)
        val settings = outbound.optJSONObject("settings")
            ?: throw IllegalArgumentException("节点缺少 outbound settings")
        when {
            settings.has("address") -> settings.put("address", candidateIp)
            settings.optJSONArray("vnext")?.length() == 1 -> {
                settings.getJSONArray("vnext").getJSONObject(0).put("address", candidateIp)
            }
            else -> throw IllegalArgumentException("节点地址结构不受支持")
        }
        return config.toString()
    }

    fun pingBatchRequest(configPath: String): String = JSONObject()
        .put("apiVersion", LIBXRAY_API_VERSION)
        .put("method", "pingBatch")
        .put(
            "payload",
            JSONObject()
                .put(
                    "configs",
                    JSONArray().put(
                        JSONObject()
                            .put("configPath", configPath)
                            .put("outboundTag", "")
                    )
                )
                .put("timeout", PING_TIMEOUT_SECONDS)
                .put("url", DELAY_TEST_URL)
        )
        .toString()

    fun pingResult(responseText: String): XrayPingResult {
        val response = try {
            JSONObject(responseText)
        } catch (_: Exception) {
            return XrayPingResult(false, error = "Xray 延迟测试响应无效")
        }
        if (!response.optBoolean("success", false)) {
            return XrayPingResult(false, error = safeError(response.optString("error"), "Xray 延迟测试失败"))
        }
        val results = response.optJSONObject("data")?.optJSONArray("results")
            ?: return XrayPingResult(false, error = "Xray 未返回节点测试结果")
        if (results.length() != 1) return XrayPingResult(false, error = "Xray 节点测试结果数量异常")
        val item = results.optJSONObject(0)
            ?: return XrayPingResult(false, error = "Xray 节点测试结果无效")
        val delay = item.optLong("delay", -1L)
        val ok = item.optBoolean("success", false) && delay in 0 until 10_000L
        return if (ok) {
            XrayPingResult(true, delayMs = delay)
        } else {
            XrayPingResult(false, delayMs = delay, error = safeError(item.optString("error"), "V2rayNG 同等节点延迟测试未通过"))
        }
    }

    private fun onlyOutbound(config: JSONObject): JSONObject {
        val outbounds = config.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("节点没有 outbound 配置")
        if (outbounds.length() != 1) throw IllegalArgumentException("每次只能粘贴一个 VMess/VLESS 节点")
        return outbounds.optJSONObject(0)
            ?: throw IllegalArgumentException("节点 outbound 配置无效")
    }

    private fun ensureAddressSlot(outbound: JSONObject) {
        val settings = outbound.optJSONObject("settings")
            ?: throw IllegalArgumentException("节点缺少 outbound settings")
        val hasDirectAddress = settings.optString("address").isNotBlank()
        val vnext = settings.optJSONArray("vnext")
        val hasVnextAddress = vnext?.length() == 1 &&
            vnext.optJSONObject(0)?.optString("address").orEmpty().isNotBlank()
        if (!hasDirectAddress && !hasVnextAddress) {
            throw IllegalArgumentException("节点缺少可替换的 server/address")
        }
    }

    private fun parseObject(text: String, message: String): JSONObject = try {
        JSONObject(text)
    } catch (_: Exception) {
        throw IllegalArgumentException(message)
    }

    private fun safeError(raw: String, fallback: String): String {
        val oneLine = raw.lineSequence().firstOrNull().orEmpty().trim()
        if (oneLine.isBlank()) return fallback
        return oneLine.replace(Regex("[/\\\\][^ ]*rr-xray-[^ ]*\\.json"), "临时配置").take(180)
    }
}

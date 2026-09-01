package com.xiaowu7z.cfipoptimizer.engine

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/**
 * Non-secret routing fields required to prove that a candidate IP can carry an
 * existing VMess/VLESS WebSocket-over-TLS node. UUIDs and user-info are never
 * retained in this object.
 */
data class NodeRouteTemplate(
    val protocol: String,
    val sni: String,
    val hostHeader: String,
    val port: Int,
    val wsPath: String
) {
    val safeSummary: String
        get() = "$protocol · $sni:$port · WS Path 已识别"
}

object NodeRouteParser {
    private val supportedPorts = setOf(443, 2053, 2083, 2087, 2096, 8443)

    fun parse(input: String): NodeRouteTemplate {
        val raw = input.trim()
        return when {
            raw.startsWith("vmess://", ignoreCase = true) -> parseVmess(raw)
            raw.startsWith("vless://", ignoreCase = true) -> parseVless(raw)
            else -> throw IllegalArgumentException("请粘贴完整的 vmess:// 或 vless:// 节点链接")
        }
    }

    private fun parseVmess(raw: String): NodeRouteTemplate {
        val encoded = raw.substringAfter("://").substringBefore('#').trim()
        val decoded = decodeBase64(encoded).toString(StandardCharsets.UTF_8)
        val json = try {
            JSONObject(decoded)
        } catch (_: Exception) {
            throw IllegalArgumentException("VMess 节点内容不是有效 JSON")
        }
        fun value(name: String): String = json.optString(name, "").trim()
        val network = value("net").lowercase(Locale.ROOT)
        if (network != "ws") throw IllegalArgumentException("当前只支持 VMess/VLESS 的 WebSocket 节点")
        val security = value("tls").ifBlank { value("security") }.lowercase(Locale.ROOT)
        if (security !in setOf("tls", "xtls")) throw IllegalArgumentException("节点必须启用 TLS，才能验证 Cloudflare Argo 入口")
        val port = value("port").toIntOrNull() ?: 443
        val rawHost = value("host").substringBefore(',').trim()
        val rawSni = value("sni").ifBlank { rawHost }.ifBlank { value("add") }
        return build("VMess", rawSni, rawHost.ifBlank { rawSni }, port, value("path"))
    }

    private fun parseVless(raw: String): NodeRouteTemplate {
        val uri = try {
            URI(raw.substringBefore('#'))
        } catch (_: Exception) {
            throw IllegalArgumentException("VLESS 节点链接格式无效")
        }
        val query = parseQuery(uri.rawQuery.orEmpty())
        val network = query["type"].orEmpty().ifBlank { "tcp" }.lowercase(Locale.ROOT)
        if (network != "ws") throw IllegalArgumentException("当前只支持 VMess/VLESS 的 WebSocket 节点")
        val security = query["security"].orEmpty().lowercase(Locale.ROOT)
        if (security !in setOf("tls", "xtls")) throw IllegalArgumentException("节点必须启用 TLS，不能是 Reality 或明文节点")
        val rawHost = query["host"].orEmpty().substringBefore(',').trim()
        val rawSni = query["sni"].orEmpty().ifBlank { rawHost }.ifBlank { uri.host.orEmpty() }
        val port = if (uri.port > 0) uri.port else 443
        return build("VLESS", rawSni, rawHost.ifBlank { rawSni }, port, query["path"].orEmpty())
    }

    private fun build(protocol: String, rawSni: String, rawHost: String, port: Int, rawPath: String): NodeRouteTemplate {
        if (port !in supportedPorts) {
            throw IllegalArgumentException("节点端口必须是 Cloudflare HTTPS 端口：443/2053/2083/2087/2096/8443")
        }
        val sni = AuthorizedHost.normalizeHost(rawSni)
        val host = AuthorizedHost.normalizeHost(rawHost)
        val path = AuthorizedHost.normalizeWsPath(rawPath.ifBlank { "/" })
        return NodeRouteTemplate(protocol, sni, host, port, path)
    }

    private fun parseQuery(raw: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        raw.split('&').filter { it.isNotBlank() }.forEach { part ->
            val key = decodeUrl(part.substringBefore('='))
            val value = decodeUrl(part.substringAfter('=', ""))
            if (key.isNotBlank()) result.putIfAbsent(key, value)
        }
        return result
    }

    private fun decodeUrl(value: String): String = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    } catch (_: Exception) {
        throw IllegalArgumentException("节点链接包含无效的 URL 转义")
    }

    private fun decodeBase64(value: String): ByteArray {
        val normalized = value.replace('-', '+').replace('_', '/').filterNot { it.isWhitespace() }
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return try {
            Base64.getDecoder().decode(padded)
        } catch (_: Exception) {
            throw IllegalArgumentException("VMess 节点 Base64 内容无效")
        }
    }
}

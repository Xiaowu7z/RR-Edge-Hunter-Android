package com.xiaowu7z.cfipoptimizer

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.IDN
import java.net.Proxy
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/** A token whose string representation is always redacted. */
class CloudflareApiToken private constructor(private val value: String) {
    internal fun <T> use(block: (String) -> T): T = block(value)

    override fun toString(): String = "CloudflareApiToken(<redacted>)"

    companion object {
        fun parse(input: String): CloudflareApiToken {
            val value = input.trim()
            require(value.length in 20..512 && value.none { it.isWhitespace() }) {
                "API Token 格式无效"
            }
            return CloudflareApiToken(value)
        }
    }
}

/**
 * Safe two-phase Cloudflare DNS synchronizer.
 *
 * [inspect] is read-only and produces a plan. The UI must show that plan and obtain
 * a second confirmation before invoking [apply]. [apply] lists the records again to
 * prevent stale-preview overwrites, then reads the record back after the write.
 */
object CloudflareDns {
    private val zoneIdPattern = Regex("^[0-9a-fA-F]{32}$")
    private val recordIdPattern = Regex("^[0-9a-fA-F]{32}$")

    class Config(
        val zoneId: String,
        val recordName: String,
        internal val token: CloudflareApiToken
    ) {
        constructor(zoneId: String, recordName: String, apiToken: String) :
            this(zoneId, recordName, CloudflareApiToken.parse(apiToken))

        override fun toString(): String =
            "Config(zoneId=$zoneId, recordName=$recordName, apiToken=<redacted>)"
    }

    enum class RecordType { A, AAAA }
    enum class Action { CREATE, UPDATE, UNCHANGED }

    data class DnsRecord(
        val id: String,
        val type: String,
        val name: String,
        val content: String,
        val ttl: Int,
        val proxied: Boolean?
    )

    /** Fields sent by PATCH. Name/type are absent so every unmodified field survives. */
    data class PatchFields(val content: String, val ttl: Int = 1, val proxied: Boolean = false)

    data class CreateFields(
        val type: RecordType,
        val name: String,
        val content: String,
        val ttl: Int = 1,
        val proxied: Boolean = false
    )

    data class SyncPlan(
        val action: Action,
        val type: RecordType,
        val zoneId: String,
        val name: String,
        val content: String,
        val existingRecordId: String?,
        val previousContent: String?,
        internal val stateFingerprint: String
    ) {
        val requiresWrite: Boolean get() = action != Action.UNCHANGED

        val confirmationText: String
            get() = when (action) {
                Action.CREATE -> "将创建 ${type.name} 记录：$name → $content（仅 DNS，TTL 自动）"
                Action.UPDATE -> "将更新 ${type.name} 记录：$name：" +
                    "${previousContent ?: "现有值"} → $content（仅 DNS，TTL 自动）"
                Action.UNCHANGED -> "${type.name} 记录已经是 $content（仅 DNS，TTL 自动），无需更新"
            }
    }

    data class SyncResult(
        val action: Action,
        val type: RecordType,
        val name: String,
        val content: String,
        val recordId: String,
        val readBackVerified: Boolean = true
    )

    /** Injectable at record level; test transports never need a plaintext token string. */
    interface Transport {
        fun listExact(token: CloudflareApiToken, zoneId: String, name: String): List<DnsRecord>
        fun create(token: CloudflareApiToken, zoneId: String, fields: CreateFields): DnsRecord
        fun patch(
            token: CloudflareApiToken,
            zoneId: String,
            recordId: String,
            fields: PatchFields
        ): DnsRecord
        fun get(token: CloudflareApiToken, zoneId: String, recordId: String): DnsRecord
    }

    fun normalizeConfig(config: Config): Config {
        val zone = config.zoneId.trim().lowercase(Locale.ROOT)
        require(zoneIdPattern.matches(zone)) { "Zone ID 必须是 32 位十六进制字符" }
        return Config(zone, normalizeRecordName(config.recordName), config.token)
    }

    fun normalizeRecordName(input: String): String {
        val raw = input.trim().trimEnd('.')
        require(raw.length in 3..253 && raw.contains('.') &&
            raw.none { it == '/' || it == ':' || it == '@' || it.isISOControl() }
        ) { "请填写完整 DNS 记录名" }
        val ascii = try {
            IDN.toASCII(raw, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        } catch (_: Exception) {
            throw IllegalArgumentException("DNS 记录名格式无效")
        }
        require(ascii.length <= 253 && ascii.split('.').all { label ->
            label.length in 1..63 && label.first().isLetterOrDigit() &&
                label.last().isLetterOrDigit() && label.all { it.isLetterOrDigit() || it == '-' }
        }) { "DNS 记录名格式无效" }
        require(IpSources.parseLiteralAddress(ascii) == null) { "DNS 记录名不能是 IP" }
        return ascii
    }

    /** Read-only. This method never creates, updates, or deletes a DNS record. */
    fun inspect(
        configInput: Config,
        championIp: String,
        transport: Transport = OkHttpTransport
    ): SyncPlan {
        val config = normalizeConfig(configInput)
        val ip = IpSources.normalizeIp(championIp)
        require(IpSources.isPublicAddress(IpSources.parseLiteralAddress(ip)!!)) {
            "只允许同步本轮严格复测确认的安全公网 IP"
        }
        val type = if (ip.contains(':')) RecordType.AAAA else RecordType.A
        return inspectNormalized(config, type, ip, transport)
    }

    /** Apply only after the UI has shown [SyncPlan.confirmationText] and confirmed. */
    fun apply(
        configInput: Config,
        confirmedPlan: SyncPlan,
        transport: Transport = OkHttpTransport
    ): SyncResult {
        val config = normalizeConfig(configInput)
        require(config.zoneId == confirmedPlan.zoneId && config.recordName == confirmedPlan.name) {
            "DNS 配置与已确认预览不一致，请重新预览"
        }
        val canonicalIp = IpSources.normalizeIp(confirmedPlan.content)
        require(IpSources.isPublicAddress(IpSources.parseLiteralAddress(canonicalIp)!!)) {
            "只允许同步本轮严格复测确认的安全公网 IP"
        }
        val expectedType = if (canonicalIp.contains(':')) RecordType.AAAA else RecordType.A
        require(expectedType == confirmedPlan.type) { "已确认预览中的记录类型无效" }

        // TOCTOU guard: require the current state to match exactly what was previewed.
        val freshPlan = inspectNormalized(config, expectedType, canonicalIp, transport)
        if (freshPlan.action != confirmedPlan.action ||
            freshPlan.existingRecordId != confirmedPlan.existingRecordId ||
            freshPlan.stateFingerprint != confirmedPlan.stateFingerprint
        ) {
            throw IllegalStateException("DNS 记录已发生变化，请重新预览并确认")
        }

        if (confirmedPlan.action == Action.UNCHANGED) {
            val recordId = confirmedPlan.existingRecordId
                ?: throw IllegalStateException("已确认预览缺少记录 ID")
            val readBack = safeTransport { transport.get(config.token, config.zoneId, recordId) }
            verifyReadBack(readBack, recordId, expectedType, config.recordName, canonicalIp)
            return SyncResult(
                Action.UNCHANGED,
                expectedType,
                config.recordName,
                canonicalIp,
                recordId
            )
        }

        val written = safeTransport {
            when (confirmedPlan.action) {
                Action.CREATE -> transport.create(
                    config.token,
                    config.zoneId,
                    CreateFields(expectedType, config.recordName, canonicalIp)
                )
                Action.UPDATE -> transport.patch(
                    config.token,
                    config.zoneId,
                    confirmedPlan.existingRecordId
                        ?: throw IllegalStateException("已确认预览缺少记录 ID"),
                    PatchFields(canonicalIp)
                )
                Action.UNCHANGED -> error("unreachable")
            }
        }
        val recordId = validateRecordId(written.id)
        val readBack = safeTransport { transport.get(config.token, config.zoneId, recordId) }
        verifyReadBack(readBack, recordId, expectedType, config.recordName, canonicalIp)
        return SyncResult(
            action = confirmedPlan.action,
            type = expectedType,
            name = config.recordName,
            content = canonicalIp,
            recordId = recordId
        )
    }

    private fun inspectNormalized(
        config: Config,
        type: RecordType,
        ip: String,
        transport: Transport
    ): SyncPlan {
        val listed = safeTransport {
            transport.listExact(config.token, config.zoneId, config.recordName)
        }
        val exact = listed.filter { normalizeUpstreamName(it.name) == config.recordName }
        val protectedConflict = exact.firstOrNull {
            it.type.equals("CNAME", ignoreCase = true) || it.type.equals("NS", ignoreCase = true)
        }
        if (protectedConflict != null) {
            throw IllegalStateException(
                "同名 ${protectedConflict.type.uppercase(Locale.ROOT)} 记录与 A/AAAA 冲突；" +
                    "为避免中断业务，工具不会自动删除或转换它"
            )
        }
        val sameType = exact.filter { it.type.equals(type.name, ignoreCase = true) }
        require(sameType.size <= 1) {
            "找到多条同名 ${type.name} 记录，为避免误更新已停止"
        }
        val current = sameType.singleOrNull()
        val currentId = current?.let { validateRecordId(it.id) }
        val unchanged = current != null &&
            runCatching { IpSources.normalizeIp(current.content) }.getOrNull() == ip &&
            current.ttl == 1 && current.proxied == false
        return SyncPlan(
            action = when {
                current == null -> Action.CREATE
                unchanged -> Action.UNCHANGED
                else -> Action.UPDATE
            },
            type = type,
            zoneId = config.zoneId,
            name = config.recordName,
            content = ip,
            existingRecordId = currentId,
            previousContent = current?.content,
            stateFingerprint = fingerprint(exact)
        )
    }

    private fun normalizeUpstreamName(value: String): String = try {
        normalizeRecordName(value)
    } catch (_: Exception) {
        throw IllegalStateException("Cloudflare 返回了无法验证的 DNS 记录")
    }

    private fun validateRecordId(value: String): String {
        val id = value.lowercase(Locale.ROOT)
        if (!recordIdPattern.matches(id)) {
            throw IllegalStateException("Cloudflare 返回了无效的记录 ID")
        }
        return id
    }

    private fun fingerprint(records: List<DnsRecord>): String {
        val canonical = records.map { record ->
            listOf(
                record.id.lowercase(Locale.ROOT),
                record.type.uppercase(Locale.ROOT),
                normalizeUpstreamName(record.name),
                record.content,
                record.ttl.toString(),
                record.proxied?.toString().orEmpty()
            ).joinToString("\u0000")
        }.sorted().joinToString("\u0001")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun verifyReadBack(
        record: DnsRecord,
        expectedId: String,
        expectedType: RecordType,
        expectedName: String,
        expectedIp: String
    ) {
        val actualIp = runCatching { IpSources.normalizeIp(record.content) }.getOrNull()
        val verified = record.id.equals(expectedId, ignoreCase = true) &&
            record.type.equals(expectedType.name, ignoreCase = true) &&
            normalizeUpstreamName(record.name) == expectedName &&
            actualIp == expectedIp && record.ttl == 1 && record.proxied == false
        if (!verified) throw IllegalStateException("DNS 写入后的回读校验失败，请在 Cloudflare 控制台确认")
    }

    private inline fun <T> safeTransport(block: () -> T): T = try {
        block()
    } catch (e: SanitizedTransportException) {
        throw IllegalStateException(e.message ?: "Cloudflare API 请求失败")
    } catch (_: Exception) {
        // Never include the upstream error/cause: it could contain a request header.
        throw IllegalStateException("Cloudflare API 连接失败")
    }

    private class SanitizedTransportException(message: String) : IllegalStateException(message)

    /** Fixed Cloudflare endpoint, no redirects/proxy, bounded response body. */
    private object OkHttpTransport : Transport {
        private const val API_HOST = "api.cloudflare.com"
        private const val MAX_RESPONSE_BYTES = 256 * 1024L
        private const val MAX_PAGES = 10
        private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        private val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

        override fun listExact(
            token: CloudflareApiToken,
            zoneId: String,
            name: String
        ): List<DnsRecord> {
            val records = mutableListOf<DnsRecord>()
            var page = 1
            var totalPages: Int
            do {
                val url = collectionUrl(zoneId).newBuilder()
                    .addQueryParameter("name.exact", name)
                    .addQueryParameter("match", "all")
                    .addQueryParameter("page", page.toString())
                    .addQueryParameter("per_page", "100")
                    .build()
                val root = call(token, "GET", url)
                val result = root["result"] as? List<*>
                    ?: throw SanitizedTransportException("Cloudflare API 响应格式无效")
                records += result.map { parseRecord(it) }
                val info = root["result_info"] as? Map<*, *>
                totalPages = (info?.get("total_pages") as? Number)?.toInt() ?: 1
                if (totalPages !in 1..MAX_PAGES) {
                    throw SanitizedTransportException("同名 DNS 记录过多，为避免误操作已停止")
                }
                page++
            } while (page <= totalPages)
            return records
        }

        override fun create(
            token: CloudflareApiToken,
            zoneId: String,
            fields: CreateFields
        ): DnsRecord {
            val body = "{" +
                "\"type\":\"${fields.type.name}\"," +
                "\"name\":\"${jsonEscape(fields.name)}\"," +
                "\"content\":\"${jsonEscape(fields.content)}\"," +
                "\"ttl\":1,\"proxied\":false}"
            return parseRecord(call(token, "POST", collectionUrl(zoneId), body)["result"])
        }

        override fun patch(
            token: CloudflareApiToken,
            zoneId: String,
            recordId: String,
            fields: PatchFields
        ): DnsRecord {
            val body = "{" +
                "\"content\":\"${jsonEscape(fields.content)}\"," +
                "\"ttl\":1,\"proxied\":false}"
            return parseRecord(call(token, "PATCH", recordUrl(zoneId, recordId), body)["result"])
        }

        override fun get(
            token: CloudflareApiToken,
            zoneId: String,
            recordId: String
        ): DnsRecord = parseRecord(call(token, "GET", recordUrl(zoneId, recordId))["result"])

        private fun collectionUrl(zoneId: String): HttpUrl = HttpUrl.Builder()
            .scheme("https")
            .host(API_HOST)
            .addPathSegment("client")
            .addPathSegment("v4")
            .addPathSegment("zones")
            .addPathSegment(zoneId)
            .addPathSegment("dns_records")
            .build()

        private fun recordUrl(zoneId: String, recordId: String): HttpUrl =
            collectionUrl(zoneId).newBuilder().addPathSegment(recordId).build()

        private fun call(
            token: CloudflareApiToken,
            method: String,
            url: HttpUrl,
            body: String? = null
        ): Map<*, *> {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .apply {
                    token.use { header("Authorization", "Bearer $it") }
                    when (method) {
                        "GET" -> get()
                        "POST" -> post((body ?: "").toRequestBody(jsonMediaType))
                        "PATCH" -> patch((body ?: "").toRequestBody(jsonMediaType))
                        else -> throw SanitizedTransportException("不支持的 Cloudflare API 请求")
                    }
                }
                .build()
            var responseText = ""
            var responseCode = 0
            try {
                client.newCall(request).execute().use { response ->
                    responseCode = response.code
                    val declared = response.header("Content-Length")?.toLongOrNull()
                    if (declared != null && declared > MAX_RESPONSE_BYTES) {
                        throw SanitizedTransportException("Cloudflare API 响应过大")
                    }
                    val source = response.body?.source()
                    responseText = if (source == null) "" else {
                        source.request(MAX_RESPONSE_BYTES + 1)
                        if (source.buffer.size > MAX_RESPONSE_BYTES) {
                            throw SanitizedTransportException("Cloudflare API 响应过大")
                        }
                        source.readUtf8()
                    }
                }
            } catch (e: SanitizedTransportException) {
                throw e
            } catch (_: IOException) {
                throw SanitizedTransportException("Cloudflare API 连接失败")
            }
            if (responseCode !in 200..299) {
                throw SanitizedTransportException("Cloudflare API 请求失败（HTTP $responseCode）")
            }
            val root = try {
                CfDnsJson.parse(responseText) as? Map<*, *>
            } catch (_: Exception) {
                null
            } ?: throw SanitizedTransportException("Cloudflare API 响应格式无效")
            if (root["success"] != true) {
                throw SanitizedTransportException("Cloudflare API 请求未成功（HTTP $responseCode）")
            }
            return root
        }

        private fun parseRecord(value: Any?): DnsRecord {
            val obj = value as? Map<*, *>
                ?: throw SanitizedTransportException("Cloudflare API 响应缺少 DNS 记录")
            val id = obj["id"] as? String
                ?: throw SanitizedTransportException("Cloudflare API 响应缺少记录 ID")
            val type = obj["type"] as? String
                ?: throw SanitizedTransportException("Cloudflare API 响应缺少记录类型")
            val name = obj["name"] as? String
                ?: throw SanitizedTransportException("Cloudflare API 响应缺少记录名")
            val content = obj["content"] as? String
                ?: throw SanitizedTransportException("Cloudflare API 响应缺少记录内容")
            val ttl = (obj["ttl"] as? Number)?.toInt()
                ?: throw SanitizedTransportException("Cloudflare API 响应缺少 TTL")
            return DnsRecord(id, type, name, content, ttl, obj["proxied"] as? Boolean)
        }

        private fun jsonEscape(value: String): String = buildString(value.length + 8) {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000c' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
                }
            }
        }
    }
}

/** Small strict JSON reader used only for bounded Cloudflare API responses. */
private object CfDnsJson {
    fun parse(text: String): Any? = Parser(text).parse()

    private class Parser(private val text: String) {
        private var index = 0

        fun parse(): Any? {
            val parsed = value(0)
            whitespace()
            if (index != text.length) error("trailing JSON")
            return parsed
        }

        private fun value(depth: Int): Any? {
            if (depth > 64) error("JSON too deep")
            whitespace()
            if (index >= text.length) error("unexpected end")
            return when (text[index]) {
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                '-', in '0'..'9' -> numberValue()
                else -> error("invalid JSON")
            }
        }

        private fun objectValue(depth: Int): Map<String, Any?> {
            expect('{')
            val result = linkedMapOf<String, Any?>()
            whitespace()
            if (take('}')) return result
            while (true) {
                whitespace()
                val key = stringValue()
                whitespace()
                expect(':')
                result[key] = value(depth)
                whitespace()
                if (take('}')) return result
                expect(',')
            }
        }

        private fun arrayValue(depth: Int): List<Any?> {
            expect('[')
            val result = mutableListOf<Any?>()
            whitespace()
            if (take(']')) return result
            while (true) {
                result += value(depth)
                whitespace()
                if (take(']')) return result
                expect(',')
            }
        }

        private fun stringValue(): String {
            expect('"')
            val out = StringBuilder()
            while (index < text.length) {
                val char = text[index++]
                when (char) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (index >= text.length) error("bad escape")
                        when (val escaped = text[index++]) {
                            '"', '\\', '/' -> out.append(escaped)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) error("bad unicode escape")
                                out.append(text.substring(index, index + 4).toInt(16).toChar())
                                index += 4
                            }
                            else -> error("bad escape")
                        }
                    }
                    else -> {
                        if (char.code < 0x20) error("control in string")
                        out.append(char)
                    }
                }
            }
            error("unterminated string")
        }

        private fun numberValue(): Number {
            val start = index
            if (text[index] == '-') index++
            while (index < text.length && text[index].isDigit()) index++
            var decimal = false
            if (index < text.length && text[index] == '.') {
                decimal = true
                index++
                while (index < text.length && text[index].isDigit()) index++
            }
            if (index < text.length && text[index] in listOf('e', 'E')) {
                decimal = true
                index++
                if (index < text.length && text[index] in listOf('+', '-')) index++
                while (index < text.length && text[index].isDigit()) index++
            }
            val raw = text.substring(start, index)
            return if (decimal) raw.toDouble() else raw.toLong()
        }

        private fun <T> literal(expected: String, value: T): T {
            if (!text.startsWith(expected, index)) error("invalid literal")
            index += expected.length
            return value
        }

        private fun whitespace() {
            while (index < text.length && text[index] in listOf(' ', '\t', '\r', '\n')) index++
        }

        private fun expect(char: Char) {
            whitespace()
            if (index >= text.length || text[index] != char) error("expected $char")
            index++
        }

        private fun take(char: Char): Boolean {
            if (index < text.length && text[index] == char) {
                index++
                return true
            }
            return false
        }
    }
}

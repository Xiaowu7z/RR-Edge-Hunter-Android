package com.xiaowu7z.cfipoptimizer

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.IDN
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Injectable DNS seam for deterministic validation tests and checked re-resolution. */
fun interface IpSubscriptionResolver {
    fun resolve(hostname: String): List<InetAddress>
}

data class ValidatedIpSubscriptionUrl(
    val uri: URI,
    val host: String,
    val addresses: List<InetAddress>,
    val literalHost: Boolean
)

data class IpSubscriptionImportResult(
    val parsed: IpParseResult,
    val finalUrl: String
)

/**
 * HTTPS-only IP subscription downloader.
 *
 * Every hop is parsed and DNS-checked before use. The HTTP client has automatic
 * redirects and proxy use disabled, pins the first public answer set, then resolves
 * once more immediately before connecting. This prevents redirect, proxy and DNS
 * rebinding paths from reaching loopback, private, link-local or metadata services.
 */
object IpSubscription {
    const val TIMEOUT_SECONDS = 12L
    const val MAX_REDIRECTS = 3

    private val hostnameLabel = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
    private val systemResolver = IpSubscriptionResolver { hostname ->
        InetAddress.getAllByName(hostname).toList()
    }

    fun validateUrl(
        value: String,
        resolver: IpSubscriptionResolver = systemResolver
    ): ValidatedIpSubscriptionUrl {
        val input = value.trim()
        val parsed = try {
            URI(input)
        } catch (_: Exception) {
            throw IpSourceException("订阅链接格式无效")
        }
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
        if (scheme != "https") throw IpSourceException("订阅链接只支持 HTTPS")
        if (parsed.rawAuthority.isNullOrBlank() || parsed.userInfo != null || parsed.rawAuthority!!.contains('@')) {
            throw IpSourceException("订阅链接不能包含账号或密码")
        }
        if (parsed.port !in listOf(-1, 443)) {
            throw IpSourceException("订阅链接只允许 HTTPS 默认 443 端口")
        }
        val rawHost = (parsed.host ?: authorityHost(parsed.rawAuthority!!))
            .removePrefix("[").removeSuffix("]")
        if (rawHost.isBlank() || rawHost.contains('%')) throw IpSourceException("订阅链接主机名无效")

        val literal = IpSources.parseLiteralAddress(rawHost)
        val host = if (literal != null) {
            if (!IpSources.isPublicAddress(literal)) {
                throw IpSourceException("订阅链接不能指向本机、内网、链路本地或保留地址")
            }
            IpSources.canonicalAddress(literal)
        } else {
            normalizeHostname(rawHost)
        }
        if (isMetadataHostname(host)) {
            throw IpSourceException("订阅链接不能指向元数据服务")
        }

        val addresses = if (literal != null) listOf(literal) else resolvePublic(host, resolver)
        val displayHost = if (literal is java.net.Inet6Address) "[$host]" else host
        val displayPort = if (parsed.port >= 0) ":${parsed.port}" else ""
        val rawPath = parsed.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val rawQuery = parsed.rawQuery?.let { "?$it" }.orEmpty()
        val normalized = try {
            URI("https://$displayHost$displayPort$rawPath$rawQuery")
        } catch (_: Exception) {
            throw IpSourceException("订阅链接格式无效")
        }
        return ValidatedIpSubscriptionUrl(normalized, host, addresses, literal != null)
    }

    /** Re-checks DNS immediately before a connection while still using the pinned initial set. */
    fun revalidateUrl(
        validated: ValidatedIpSubscriptionUrl,
        resolver: IpSubscriptionResolver = systemResolver
    ) {
        if (validated.literalHost) {
            if (validated.addresses.any { !IpSources.isPublicAddress(it) }) {
                throw IpSourceException("订阅链接不能指向本机、内网、链路本地或保留地址")
            }
            return
        }
        resolvePublic(validated.host, resolver)
    }

    fun fetch(
        value: String,
        resolver: IpSubscriptionResolver = systemResolver
    ): IpSubscriptionImportResult {
        var current = validateUrl(value, resolver)
        var redirects = 0
        while (true) {
            val client = pinnedClient(current, resolver)
            val request = Request.Builder()
                .url(current.uri.toASCIIString())
                .header("Accept", "text/plain, application/json, text/csv, application/octet-stream")
                .header("User-Agent", "CF-IP-Optimizer-Android/1.0")
                .get()
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (response.code in 300..399) {
                        if (redirects >= MAX_REDIRECTS) throw IpSourceException("订阅重定向次数过多")
                        val location = response.header("Location")
                            ?: throw IpSourceException("订阅重定向缺少目标地址")
                        val next = try {
                            current.uri.resolve(location).toString()
                        } catch (_: Exception) {
                            throw IpSourceException("订阅重定向地址无效")
                        }
                        current = validateUrl(next, resolver)
                        redirects++
                        return@use
                    }
                    if (!response.isSuccessful) throw IpSourceException("订阅链接返回 HTTP ${response.code}")
                    revalidateUrl(current, resolver)
                    val contentType = response.header("Content-Type").orEmpty()
                        .substringBefore(';').trim().lowercase(Locale.ROOT)
                    if (contentType == "text/html" || contentType == "application/xhtml+xml") {
                        throw IpSourceException("订阅链接返回了网页，不是 IP 列表")
                    }
                    val contentLength = response.header("Content-Length")?.toLongOrNull()
                    if (contentLength != null && contentLength > IpSources.MAX_SOURCE_BYTES) {
                        throw IpSourceException("订阅内容不能超过 1 MiB")
                    }
                    val body = response.body ?: throw IpSourceException("订阅内容为空")
                    val payload = readLimited(body.byteStream())
                    val filename = dispositionFilename(response.header("Content-Disposition"))
                        ?: current.uri.path.substringAfterLast('/').ifEmpty { "subscription" }
                    return IpSubscriptionImportResult(
                        parsed = IpSources.parseBytes(payload, filename),
                        finalUrl = current.uri.toASCIIString()
                    )
                }
            } catch (e: IpSourceException) {
                throw e
            } catch (e: IOException) {
                throw IpSourceException("订阅下载失败：${e.message?.take(120) ?: e.javaClass.simpleName}")
            }
        }
    }

    fun isPublicAddress(address: InetAddress): Boolean = IpSources.isPublicAddress(address)

    private fun pinnedClient(
        validated: ValidatedIpSubscriptionUrl,
        resolver: IpSubscriptionResolver
    ): OkHttpClient {
        val pinnedHost = validated.host
        val pinnedAddresses = validated.addresses.toList()
        val dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (!hostname.equals(pinnedHost, ignoreCase = true)) {
                    throw UnknownHostException("未校验的订阅主机")
                }
                try {
                    revalidateUrl(validated, resolver)
                } catch (e: IpSourceException) {
                    throw UnknownHostException(e.message ?: "订阅主机重新解析失败")
                }
                return pinnedAddresses
            }
        }
        return OkHttpClient.Builder()
            .dns(dns)
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS + 3, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private fun resolvePublic(host: String, resolver: IpSubscriptionResolver): List<InetAddress> {
        val addresses = try {
            resolver.resolve(host).distinctBy { IpSources.canonicalAddress(it) }
        } catch (_: Exception) {
            throw IpSourceException("订阅域名解析失败")
        }
        if (addresses.isEmpty()) throw IpSourceException("订阅域名没有可用地址")
        if (addresses.any { !IpSources.isPublicAddress(it) }) {
            throw IpSourceException("订阅链接不能指向本机、内网、链路本地或保留地址")
        }
        return addresses
    }

    private fun normalizeHostname(value: String): String {
        val raw = value.trim().trimEnd('.')
        if (raw.isEmpty() || raw.length > 253 || raw.contains(':') || raw.contains('/') || raw.contains('@')) {
            throw IpSourceException("订阅链接主机名无效")
        }
        val ascii = try {
            IDN.toASCII(raw, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        } catch (_: Exception) {
            throw IpSourceException("订阅链接主机名无效")
        }
        if (!ascii.contains('.') || ascii.split('.').any { !hostnameLabel.matches(it) }) {
            throw IpSourceException("订阅链接主机名无效")
        }
        return ascii
    }

    private fun isMetadataHostname(host: String): Boolean = host == "localhost" || host.endsWith(".localhost") ||
        host == "metadata.google.internal" || host.endsWith(".metadata.google.internal") ||
        host == "instance-data" || host.endsWith(".instance-data")

    private fun authorityHost(authority: String): String {
        val withoutUser = authority.substringAfterLast('@')
        if (withoutUser.startsWith('[')) return withoutUser.substringAfter('[').substringBefore(']')
        val port = withoutUser.substringAfterLast(':', "")
        return if (withoutUser.count { it == ':' } == 1 && port.all { it.isDigit() }) {
            withoutUser.substringBeforeLast(':')
        } else {
            withoutUser
        }
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > IpSources.MAX_SOURCE_BYTES) {
                    throw IpSourceException("订阅内容不能超过 1 MiB")
                }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private fun dispositionFilename(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return Regex("filename\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(1)?.substringAfterLast('/')?.substringAfterLast('\\')
    }
}

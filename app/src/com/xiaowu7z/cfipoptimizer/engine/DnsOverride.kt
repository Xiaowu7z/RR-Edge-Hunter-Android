package com.xiaowu7z.cfipoptimizer.engine

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale

/**
 * 受控 DNS：只对本次探针指定的主机返回已经通过安全公网地址校验的固定候选
 * IP。URL、TLS SNI、Host 与证书校验始终保持指定主机；只有 TCP
 * 连接目标会被固定。
 *
 * 这个类不是 hosts 修改器，也不会为任意域名提供覆盖。调用方必须把 mapping
 * 限制为用户的 Argo 域名或 Cloudflare 公开测速域名，并在调用前验证候选是
 * 公网字面量。外部候选仍须在输出前通过真实对端与 CF-RAY 等严格探测门禁。
 *
 * 要求：指定 IPv4 时 mapping 存 IPv4 地址（AF_INET），IPv6 时存 IPv6（AF_INET6），
 * 连接不经过 Happy Eyeballs 自动选择——OkHttp 直接使用本 Dns 返回的唯一地址。
 */
class FixedDns(private val mapping: Map<String, InetAddress>) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        mapping[hostname.lowercase(Locale.ROOT)]?.let { return listOf(it) }
        // A run has exactly one authorized hostname.  Do not fall back to the
        // system resolver here: a redirect, embedded third-party hostname, or
        // future request change must never turn this narrowly scoped probe into
        // a connection to some unrelated address.
        throw UnknownHostException("未授权的测试主机：$hostname")
    }

    companion object {
        /** 便捷构造：testHost -> 指定 IP。 */
        fun forTestHost(testHost: String, ip: String): FixedDns {
            val addr = InetAddress.getByName(ip)
            return FixedDns(mapOf(testHost.lowercase(Locale.ROOT) to addr))
        }
    }
}

package com.xiaowu7z.cfipoptimizer.engine

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import libXray.LibXray

/** Native Xray gate equivalent to V2rayNG's real proxy delay test. */
object XrayNodeGate {
    fun clearTemporaryConfigs(cacheDir: File) {
        cacheDir.listFiles { file ->
            file.isFile && file.name.startsWith("rr-xray-") && file.name.endsWith(".json")
        }?.forEach { file -> file.delete() }
    }

    @Synchronized
    fun recognize(shareLink: String): XrayNodeProfile {
        val route = NodeRouteParser.parse(shareLink)
        val response = LibXray.invoke(XrayNodeConfig.convertRequest(shareLink))
        return XrayNodeConfig.profileFromConvertResponse(route, response)
    }

    /**
     * Runs one full VMess/VLESS outbound request through [candidateIp]. Calls are
     * serialized because Xray-core owns process-global runtime state.
     */
    @Synchronized
    fun verify(cacheDir: File, profile: XrayNodeProfile, candidateIp: String): ProbeEngine.ArgoRouteResult {
        var temp: File? = null
        val route = profile.route
        return try {
            clearTemporaryConfigs(cacheDir)
            val config = XrayNodeConfig.configForCandidate(profile, candidateIp)
            temp = File.createTempFile("rr-xray-", ".json", cacheDir).apply {
                setReadable(false, false)
                setWritable(false, false)
                setReadable(true, true)
                setWritable(true, true)
            }
            FileOutputStream(temp).use { output ->
                output.write(config.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            val response = LibXray.invoke(XrayNodeConfig.pingBatchRequest(temp.absolutePath))
            val ping = XrayNodeConfig.pingResult(response)
            ProbeEngine.ArgoRouteResult(
                ok = ping.ok,
                error = ping.error,
                targetIp = candidateIp,
                sni = route.sni,
                hostHeader = route.hostHeader,
                certVerified = ping.ok,
                targetMatchesRemote = ping.ok,
                actualRemoteAddress = candidateIp,
                wsPath = route.wsPath,
                websocketAccepted = ping.ok,
                ttfbMs = ping.delayMs.toDouble()
            )
        } catch (e: Exception) {
            ProbeEngine.ArgoRouteResult(
                ok = false,
                error = (e.message ?: "Xray 节点测试失败").lineSequence().first().take(180),
                targetIp = candidateIp,
                sni = route.sni,
                hostHeader = route.hostHeader,
                wsPath = route.wsPath
            )
        } finally {
            temp?.delete()
        }
    }
}

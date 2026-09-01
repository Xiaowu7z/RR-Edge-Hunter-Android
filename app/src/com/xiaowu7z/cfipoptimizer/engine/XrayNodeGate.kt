package com.xiaowu7z.cfipoptimizer.engine

import java.io.File
import libXray.LibXray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Native Xray gate equivalent to V2rayNG's real proxy delay test. */
object XrayNodeGate {
    private val nativeLock = Any()

    fun clearTemporaryConfigs(cacheDir: File) {
        XrayTemporaryConfigStore.clearStale(cacheDir)
    }

    suspend fun recognize(shareLink: String): XrayNodeProfile {
        val job = currentCoroutineContext()[Job]
        job?.ensureActive()
        val route = NodeRouteParser.parse(shareLink)
        val response = invokeNative(XrayNodeConfig.convertRequest(shareLink), job)
        job?.ensureActive()
        return XrayNodeConfig.profileFromConvertResponse(route, response).also {
            job?.ensureActive()
        }
    }

    /**
     * Runs one full VMess/VLESS outbound request through [candidateIp]. Calls are
     * serialized because Xray-core owns process-global runtime state.
     */
    suspend fun verify(cacheDir: File, profile: XrayNodeProfile, candidateIp: String): ProbeEngine.ArgoRouteResult {
        val job = currentCoroutineContext()[Job]
        var temp: File? = null
        val route = profile.route
        return try {
            job?.ensureActive()
            clearTemporaryConfigs(cacheDir)
            val config = XrayNodeConfig.configForCandidate(profile, candidateIp)
            temp = XrayTemporaryConfigStore.create(cacheDir, config)
            val response = invokeNative(XrayNodeConfig.pingBatchRequest(temp.absolutePath), job)
            job?.ensureActive()
            val ping = XrayNodeConfig.pingResult(response)
            job?.ensureActive()
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
        } catch (error: CancellationException) {
            throw error
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
            XrayTemporaryConfigStore.release(temp)
        }
    }

    /** libXray/Xray-core owns process-global runtime state; never overlap calls. */
    private fun invokeNative(request: String, job: Job?): String = synchronized(nativeLock) {
        job?.ensureActive()
        LibXray.invoke(request)
    }
}

package com.xiaowu7z.cfipoptimizer.engine

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Owns the short-lived credential-bearing files required by libXray v26.7.28.
 *
 * That pinned libXray release accepts a config path rather than in-memory JSON.
 * Activity recreation can run stale-file cleanup while a blocking native ping
 * is still reading its file, so active paths are registered atomically and are
 * never removed by cleanup. Every normal call releases its own file in `finally`.
 */
internal object XrayTemporaryConfigStore {
    private const val PREFIX = "rr-xray-"
    private const val SUFFIX = ".json"

    private val lock = Any()
    private val activePaths = LinkedHashSet<String>()

    fun create(cacheDir: File, configJson: String): File = synchronized(lock) {
        require(cacheDir.isDirectory || cacheDir.mkdirs()) { "无法创建 Xray 临时目录" }
        val temporary = File.createTempFile(PREFIX, SUFFIX, cacheDir)
        val path = temporary.absolutePath
        activePaths.add(path)
        try {
            temporary.setReadable(false, false)
            temporary.setWritable(false, false)
            temporary.setExecutable(false, false)
            temporary.setReadable(true, true)
            temporary.setWritable(true, true)
            FileOutputStream(temporary).use { output ->
                output.write(configJson.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            temporary
        } catch (error: Exception) {
            activePaths.remove(path)
            eraseAndDelete(temporary)
            throw error
        }
    }

    fun release(temporary: File?) {
        if (temporary == null) return
        synchronized(lock) {
            activePaths.remove(temporary.absolutePath)
            eraseAndDelete(temporary)
        }
    }

    fun clearStale(cacheDir: File) {
        synchronized(lock) {
            cacheDir.listFiles { file ->
                file.isFile && file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX)
            }?.forEach { file ->
                if (file.absolutePath !in activePaths) eraseAndDelete(file)
            }
        }
    }

    internal fun isActiveForTest(temporary: File): Boolean = synchronized(lock) {
        temporary.absolutePath in activePaths
    }

    private fun eraseAndDelete(file: File) {
        if (!file.exists()) return
        try {
            FileOutputStream(file, false).use { output -> output.fd.sync() }
        } catch (_: Exception) {
            // The private cache file is still deleted below when truncation is
            // unavailable (for example, after an external cleanup race).
        }
        file.delete()
    }
}

import com.xiaowu7z.cfipoptimizer.engine.XrayTemporaryConfigStore
import java.io.File
import java.nio.file.Files

fun main() {
    val root = Files.createTempDirectory("rr-xray-store-test-").toFile()
    try {
        val active = XrayTemporaryConfigStore.create(root, "{\"outbounds\":[]}")
        val stale = File.createTempFile("rr-xray-", ".json", root).apply {
            writeText("stale-secret")
        }
        check(XrayTemporaryConfigStore.isActiveForTest(active)) { "active config was not registered" }

        XrayTemporaryConfigStore.clearStale(root)
        check(active.isFile) { "cleanup deleted a config still used by native Xray" }
        check(!stale.exists()) { "cleanup retained a stale credential file" }

        XrayTemporaryConfigStore.release(active)
        check(!active.exists()) { "released credential file still exists" }
        check(!XrayTemporaryConfigStore.isActiveForTest(active)) { "released config remained registered" }
        println("XrayTemporaryConfigStoreTest OK")
    } finally {
        root.deleteRecursively()
    }
}

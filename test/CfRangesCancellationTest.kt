import com.xiaowu7z.cfipoptimizer.engine.CfRanges
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import java.io.IOException

private class HangingCall : Call {
    val enqueued = CompletableDeferred<Unit>()

    @Volatile private var executed = false
    @Volatile private var cancelled = false

    override fun request(): Request = Request.Builder().url("https://example.invalid/ranges").build()

    override fun execute(): Response = throw AssertionError("cancellation test must not execute synchronously")

    override fun enqueue(responseCallback: Callback) {
        executed = true
        enqueued.complete(Unit)
        // Deliberately never completes: the coroutine must cancel this Call.
    }

    override fun cancel() {
        cancelled = true
    }

    override fun isExecuted(): Boolean = executed

    override fun isCanceled(): Boolean = cancelled

    override fun timeout(): Timeout = Timeout.NONE

    public override fun clone(): Call = HangingCall()
}

fun main() = runBlocking {
    var passed = 0
    var failed = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) { passed++; println("PASS  $name") } else { failed++; println("FAIL  $name  $detail") }
    }

    val call = HangingCall()
    val requestJob = launch { CfRanges.awaitRangeResponse(call) }
    call.enqueued.await()
    requestJob.cancelAndJoin()
    check("取消官方网段刷新会取消底层OkHttp Call", call.isCanceled())
    check("被取消的网段请求协程正常结束", requestJob.isCancelled)

    val enteredFetcher = CompletableDeferred<Unit>()
    var continuedAfterCancellation = false
    val refreshJob = launch {
        CfRanges.refreshWithFetcher {
            enteredFetcher.complete(Unit)
            awaitCancellation()
        }
        continuedAfterCancellation = true
    }
    enteredFetcher.await()
    refreshJob.cancelAndJoin()
    check("刷新流程不吞掉CancellationException", !continuedAfterCancellation)

    val beforeV4 = CfRanges.rangesV4
    val beforeV6 = CfRanges.rangesV6
    CfRanges.refreshWithFetcher { throw IOException("offline") }
    check(
        "普通网络失败仍保留既有安全网段",
        CfRanges.rangesV4 == beforeV4 && CfRanges.rangesV6 == beforeV6
    )

    println("CfRangesCancellationTest：PASS $passed / FAIL $failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}

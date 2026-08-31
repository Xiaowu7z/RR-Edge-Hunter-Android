package com.xiaowu7z.cfipoptimizer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.xiaowu7z.cfipoptimizer.engine.AuthorizedHost
import com.xiaowu7z.cfipoptimizer.engine.AuthorizedHostSnapshot
import com.xiaowu7z.cfipoptimizer.engine.CandidatePool
import com.xiaowu7z.cfipoptimizer.engine.CfRanges
import com.xiaowu7z.cfipoptimizer.engine.IpMetric
import com.xiaowu7z.cfipoptimizer.engine.IpPipeline
import com.xiaowu7z.cfipoptimizer.engine.ProbeEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * CF 优选IP ranks Cloudflare edge addresses from the current Android network.
 * The normal flow needs no hostname: users copy the winning literal IP into a
 * VMess/VLESS node's address/server field and leave every other node field as-is.
 */
class MainActivity : Activity() {
    companion object { private const val REQUEST_OPEN_IP_FILE = 711 }

    private class RunLease(val generation: Long) {
        var job: Job? = null
        var unregisterWatcher: (() -> Unit)? = null
    }

    // RR Edge Atlas dark / violet visual system.
    private val bg = Color.parseColor("#060811")
    private val card = Color.parseColor("#101422")
    private val cardTop = Color.parseColor("#17213B")
    private val stroke = Color.parseColor("#293452")
    private val primary = Color.parseColor("#F3F5FF")
    private val secondary = Color.parseColor("#AEB8D6")
    private val muted = Color.parseColor("#69738F")
    private val accent = Color.parseColor("#967CFF")
    private val accentSoft = Color.parseColor("#3A3267")
    private val good = Color.parseColor("#6CE6CE")
    private val warn = Color.parseColor("#F5C86C")
    private val bad = Color.parseColor("#FF7484")
    private val off = Color.parseColor("#1A2135")

    private lateinit var home: View
    private lateinit var run: View
    private lateinit var result: View
    private lateinit var status: TextView
    private lateinit var protocolSummary: TextView
    private lateinit var testHost: EditText
    private lateinit var wsPathInput: EditText
    private lateinit var argoPortInput: EditText
    private lateinit var expectedBandwidthInput: EditText
    private lateinit var advancedPanel: LinearLayout
    private lateinit var customPanel: LinearLayout
    private lateinit var customIpsInput: EditText
    private lateinit var subscriptionInput: EditText
    private lateinit var subscriptionButton: Button
    private lateinit var importStatus: TextView
    private lateinit var stage: TextView
    private lateinit var progress: ProgressBar
    private lateinit var percent: TextView
    private lateinit var logs: TextView
    private lateinit var results: LinearLayout

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val runStateLock = Any()
    private val runGeneration = AtomicLong(0L)
    private var runningJob: Job? = null
    private var activeRun: RunLease? = null
    private var currentPage = "home"
    private var unregisterNetworkWatch: (() -> Unit)? = null
    private var protocol = "IPv4"
    private var strategy = "亚洲狩猎"
    private var operatorLabel = "自动"
    private var advancedValidation = false
    private var sessionCloudflareToken: CloudflareApiToken? = null
    private var importedIps: List<String> = emptyList()
    private var importDescription = "尚未导入"
    private var appliedInput = ""
    private val logQueue = ConcurrentLinkedQueue<String>()
    private val logLines = ArrayDeque<String>()
    private val logHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val flushing = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        buildHome(); buildRun(); buildResult()
        switchTo(home, "home")
        refreshStatus()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun shape(color: Int, radius: Int, border: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat(); setColor(color); if (border != null) setStroke(dp(1), border)
    }
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { if (top > 0) topMargin = dp(top) }
    private fun label(value: String, size: Float = 13f, color: Int = secondary, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1f)
    }
    private fun heading(value: String) = label(value, 13f, primary, true).apply { setPadding(0, 0, 0, dp(8)) }
    private fun panel(block: LinearLayout.() -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); background = shape(card, 18, stroke)
        elevation = dp(2).toFloat(); block()
    }
    private fun input(hint: String, multiline: Boolean = false, password: Boolean = false) = EditText(this).apply {
        this.hint = hint; setHintTextColor(muted); setTextColor(primary); textSize = 13f
        background = shape(Color.parseColor("#0A0E19"), 12, stroke); setPadding(dp(12), dp(8), dp(12), dp(8))
        inputType = when { password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE else -> InputType.TYPE_CLASS_TEXT }
        if (multiline) { minLines = 3; maxLines = 7; gravity = Gravity.TOP }
    }
    private fun primaryButton(value: String, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 14f; setAllCaps(false); setTextColor(Color.WHITE); background = shape(accent, 12); setOnClickListener { action() }
    }
    private fun secondaryButton(value: String, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 12f; setAllCaps(false); setTextColor(secondary); background = shape(off, 12, stroke); setOnClickListener { action() }
    }
    private fun LinearLayout.addPanel(top: Int = 0, block: LinearLayout.() -> Unit) { addView(panel(block), lp(top)) }

    private fun segmented(values: List<String>, display: List<String> = values, initial: String, select: (String) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = LinkedHashMap<String, Button>()
        fun mark(value: String, notify: Boolean) {
            buttons.forEach { (key, button) ->
                val selected = key == value
                button.setTextColor(if (selected) Color.WHITE else secondary)
                button.background = shape(if (selected) accentSoft else off, 10, if (selected) accent else stroke)
            }
            if (notify) select(value)
        }
        values.forEachIndexed { index, value ->
            val button = Button(this).apply {
                text = display.getOrElse(index) { value }; textSize = 11.5f; minHeight = 0; minimumHeight = 0; setAllCaps(false)
                setPadding(dp(2), 0, dp(2), 0); setOnClickListener { mark(value, true) }
            }
            buttons[value] = button
            row.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply { if (index < values.lastIndex) rightMargin = dp(6) })
        }
        mark(initial, false)
        return row
    }

    // ------------------------------------------ home
    @SuppressLint("SetTextI18n")
    private fun buildHome() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(24)); setBackgroundColor(bg) }
        home = ScrollView(this).apply { addView(root) }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        brand.addView(label("RR", 18f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = shape(accent, 13) }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(12) })
        brand.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
            addView(label("CF 优选IP", 25f, primary, true)); addView(label("RR Edge Hunter · Android 1.0.0", 12f, muted))
        })
        root.addView(brand)
        root.addView(label("一键找到当前网络更快、更稳定的 Cloudflare 入口 IP。复制 IP 到 VMess / VLESS 节点的 address/server，其他参数全部保持原样。", 12f, secondary).apply { setPadding(0, dp(14), 0, dp(8)) })
        status = label("", 12f, muted); root.addView(status)

        root.addPanel(12) {
            addView(heading("一键扫描配置"))
            protocolSummary = label("", 12f, secondary); addView(protocolSummary)
            addView(heading("IP 协议").apply { setPadding(0, dp(12), 0, dp(8)) })
            addView(segmented(listOf("IPv4", "IPv6", "双栈"), initial = "IPv4") { protocol = it; refreshStatus() })
            addView(heading("期望带宽（Mbps）").apply { setPadding(0, dp(16), 0, dp(8)) })
            expectedBandwidthInput = input("例如 100").apply {
                setSingleLine(true); inputType = InputType.TYPE_CLASS_NUMBER; setText("100")
            }
            addView(expectedBandwidthInput)
            addView(label("用于判断是否提前结束；每个候选按约 1 秒真实下载测速。最大带宽模式会测试完整前 10 名，流量更多。", 11f, muted).apply { setPadding(0, dp(6), 0, 0) })
            addView(label("连接验证：TLS 严格校验 ✓ · 公开测速端口 443", 11.5f, good, true).apply { setPadding(0, dp(12), 0, 0) })
        }
        root.addView(primaryButton("开始扫描 Cloudflare 优选 IP") { preflightAndStart() }, lp(16))
        root.addView(secondaryButton("历史记录") { showHistory() }, lp(8))

        val advancedSettings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(secondaryButton("高级设置（策略、运营商、IP 池、Argo 复核）") {
            advancedSettings.visibility = if (advancedSettings.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }, lp(8))
        root.addView(advancedSettings)

        advancedSettings.addPanel(12) {
            addView(heading("测速策略"))
            addView(segmented(listOf("均衡", "亚洲狩猎", "最大带宽"), initial = "亚洲狩猎") { strategy = it; refreshStatus() })
            addView(label("均衡/亚洲狩猎在连续两次达到目标后提前结束；最大带宽会测完延迟前 10 名并复测最快候选。亚洲 POP 只在同档成绩中加分。", 11f, muted).apply { setPadding(0, dp(6), 0, 0) })
            addView(heading("线路标签").apply { setPadding(0, dp(16), 0, dp(8)) })
            addView(segmented(listOf("自动", "中国移动", "中国电信", "中国联通"), listOf("自动", "移动", "电信", "联通"), "自动") { operatorLabel = it; refreshStatus() })
            addView(label("标签用于历史和对比；不会模拟运营商网络或改变 IP 池。", 11f, muted).apply { setPadding(0, dp(6), 0, 0) })
            addView(label("测速固定使用 speed.cloudflare.com + TLS 443；这不会覆盖你节点原来的 2053 / 8443 等端口。", 11f, good).apply { setPadding(0, dp(10), 0, 0) })
        }

        advancedSettings.addPanel(12) {
            addView(heading("高级：按我的 Argo 节点复核（可选）"))
            addView(label("默认关闭，不需要域名也能优选 IP。只有你想确认某个 IP 是否真能接入自己的 SNI / Host / WS Path 时才开启。", 11.5f, muted))
            addView(segmented(listOf("关闭", "开启"), listOf("关闭（默认）", "开启复核"), "关闭") {
                advancedValidation = it == "开启"
                advancedPanel.visibility = if (advancedValidation) View.VISIBLE else View.GONE
                refreshStatus()
            }, lp(10))
            advancedPanel = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; visibility = View.GONE
                testHost = input("原节点 TLS SNI / WS Host 域名").apply { setSingleLine(true) }
                addView(testHost, lp(10))
                argoPortInput = input("原节点 HTTPS 端口，默认 443").apply {
                    setSingleLine(true); inputType = InputType.TYPE_CLASS_NUMBER; setText("443")
                }
                addView(argoPortInput, lp(8))
                wsPathInput = input("WS Path（可选），例如 /vless?ed=2048").apply { setSingleLine(true) }
                addView(wsPathInput, lp(8))
                addView(label("复核会用原节点端口校验 TLS/SNI/Host；填写 Path 时还必须通过 WebSocket 101。", 11f, warn).apply { setPadding(0, dp(7), 0, 0) })
            }
            addView(advancedPanel)
        }

        advancedSettings.addPanel(12) {
            addView(heading("自定义 IP 池 · 可选"))
            addView(label("无需导入：默认已包含公开测速域名 DNS 种子和 Cloudflare 官方 CIDR 分散抽样。也可长复制、上传文件或订阅 IP 池。", 11.5f, muted))
            customPanel = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE
                customIpsInput = input("粘贴 IPv4 / IPv6 / IP:443 / CIDR；支持长复制", true); addView(customIpsInput, lp(12))
                addView(primaryButton("应用粘贴内容") { applyManualIps(true) }, lp(8))
                addView(secondaryButton("导入 IP 文件") { openIpFilePicker() }, lp(8))
                addView(label("TXT / CSV / TSV / JSON / Base64；CIDR 仅受控抽样。文件和订阅最大 1 MiB。", 11f, muted).apply { setPadding(0, dp(6), 0, 0) })
                subscriptionInput = input("https://example.com/cf-ips.txt").apply { setSingleLine(true) }; addView(subscriptionInput, lp(10))
                subscriptionButton = secondaryButton("安全导入 HTTPS 订阅") { importSubscription() }
                addView(subscriptionButton, lp(8))
                importStatus = label("尚未导入", 11f, muted); addView(importStatus, lp(8))
            }
            addView(secondaryButton("添加 / 管理 IP 池") {
                customPanel.visibility = if (customPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                refreshImportStatus()
            }, lp(10))
            addView(customPanel)
        }

        advancedSettings.addPanel(12) {
            addView(heading("安全边界"))
            addView(label("• 候选仅限 Cloudflare 官方网段，并受总量、并发和流量上限约束。", 12f, secondary))
            addView(label("• 默认只测 speed.cloudflare.com，不要求你提供节点域名或源站 IP。", 12f, secondary))
            addView(label("• 默认只复制裸 IP，不改 hosts、路由或系统代理。DNS 同步必须在结果页二次确认。", 12f, secondary))
        }
    }

    private fun effectiveOperator(info: NetEnv.NetInfo) = if (operatorLabel == "自动") info.carrier.ifBlank { "自动" } else operatorLabel
    private fun refreshStatus() {
        if (!::status.isInitialized) return
        val info = NetEnv.detect(this)
        val pool = "CF 官方池${if (importedIps.isNotEmpty()) " + 导入 ${importedIps.size}" else ""}"
        status.text = "${info.label} · 线路：${effectiveOperator(info)} · 候选：$pool"
        protocolSummary.text = "$protocol · $strategy${if (advancedValidation) " · 高级复核" else ""}"
    }
    private fun refreshImportStatus(error: String? = null) {
        if (!::importStatus.isInitialized) return
        when {
            error != null -> { importStatus.text = error; importStatus.setTextColor(bad) }
            importedIps.isEmpty() -> { importStatus.text = "尚未导入；一键优选仍会使用公开测速域名 DNS 种子与 CF 官方受控抽样。"; importStatus.setTextColor(muted) }
            else -> { importStatus.text = "已导入 ${importedIps.size} 个 IP · $importDescription"; importStatus.setTextColor(good) }
        }
    }
    private fun applyManualIps(showToast: Boolean): Boolean {
        val raw = customIpsInput.text?.toString().orEmpty()
        if (raw == appliedInput && importedIps.isNotEmpty()) return true
        return try {
            applyParsedIps(IpSources.parse(raw), "手动粘贴")
            if (showToast) Toast.makeText(this, "已识别 ${importedIps.size} 个有效 IP", Toast.LENGTH_SHORT).show()
            true
        } catch (e: IpSourceException) {
            refreshImportStatus(e.message ?: "IP 内容无效"); if (showToast) Toast.makeText(this, e.message ?: "IP 内容无效", Toast.LENGTH_LONG).show(); false
        }
    }
    private fun applyParsedIps(parsed: IpParseResult, origin: String) {
        importedIps = parsed.ips
        importDescription = buildString {
            append("$origin · ${parsed.sourceFormat}")
            if (parsed.cidrCount > 0) append(" · CIDR ${parsed.cidrCount}")
            if (parsed.sampledCidrs > 0) append(" · 抽样 ${parsed.sampledCidrs}")
            if (parsed.ignored > 0) append(" · 忽略 ${parsed.ignored}")
            parsed.warnings.firstOrNull()?.let { append(" · $it") }
        }
        appliedInput = importedIps.joinToString("\n"); customIpsInput.setText(appliedInput); customIpsInput.setSelection(0)
        refreshImportStatus(); refreshStatus()
    }
    private fun openIpFilePicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "text/*"; putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/csv", "application/json", "application/octet-stream"))
        }, REQUEST_OPEN_IP_FILE)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OPEN_IP_FILE || resultCode != RESULT_OK || data?.data == null) return
        scope.launch {
            try {
                val (bytes, name) = readIpFile(data.data!!)
                val parsed = IpSources.parseBytes(bytes, name)
                runOnUiThread { applyParsedIps(parsed, "文件 $name"); Toast.makeText(this@MainActivity, "已导入 ${parsed.ips.size} 个 IP", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) { runOnUiThread { refreshImportStatus(e.message ?: "IP 文件导入失败") } }
        }
    }
    private fun readIpFile(uri: android.net.Uri): Pair<ByteArray, String> {
        var name = "ips"
        contentResolver.query(uri, null, null, null, null)?.use { cursor -> if (cursor.moveToFirst()) {
            val ni = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME); val si = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (ni >= 0 && !cursor.isNull(ni)) name = cursor.getString(ni).orEmpty().ifBlank { name }
            if (si >= 0 && !cursor.isNull(si) && cursor.getLong(si) > IpSources.MAX_SOURCE_BYTES) throw IpSourceException("IP 源文件不能超过 1 MiB")
        } }
        return contentResolver.openInputStream(uri)?.use { stream ->
            val out = ByteArrayOutputStream(); val buffer = ByteArray(16 * 1024); var total = 0
            while (true) { val n = stream.read(buffer); if (n < 0) break; total += n
                if (total > IpSources.MAX_SOURCE_BYTES) throw IpSourceException("IP 源文件不能超过 1 MiB"); out.write(buffer, 0, n) }
            out.toByteArray() to name
        } ?: throw IpSourceException("无法读取所选文件")
    }
    private fun importSubscription() {
        val url = subscriptionInput.text?.toString().orEmpty()
        if (url.isBlank()) { refreshImportStatus("请填写 HTTPS 订阅链接"); return }
        subscriptionButton.isEnabled = false; importStatus.text = "正在安全下载订阅…"; importStatus.setTextColor(secondary)
        scope.launch {
            try {
                val imported = IpSubscription.fetch(url)
                runOnUiThread { subscriptionButton.isEnabled = true; subscriptionInput.setText(imported.finalUrl); applyParsedIps(imported.parsed, "订阅 ${imported.finalUrl}") }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { runOnUiThread { subscriptionButton.isEnabled = true; refreshImportStatus(e.message ?: "订阅导入失败") } }
        }
    }

    // ------------------------------------------ run
    private fun buildRun() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(30), dp(20), dp(20)); setBackgroundColor(bg) }
        run = root
        root.addView(label("RR Edge Hunter", 23f, primary, true))
        stage = label("准备中…", 15f, accent, true).apply { setPadding(0, dp(18), 0, dp(4)) }; root.addView(stage)
        percent = label("0%", 12f, secondary); root.addView(percent)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = ColorStateList.valueOf(accent); progressBackgroundTintList = ColorStateList.valueOf(off)
        }; root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(12)).apply { topMargin = dp(8) })
        root.addView(heading("实时日志").apply { setPadding(0, dp(20), 0, dp(6)) })
        logs = label("", 11f, secondary).apply { typeface = Typeface.MONOSPACE; setPadding(dp(12), dp(12), dp(12), dp(12)); background = shape(Color.parseColor("#090D17"), 12, stroke) }
        root.addView(ScrollView(this).apply { addView(logs) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(secondaryButton("停止本次测试") { cancelActiveRun() }, lp(14))
    }
    private fun preflightAndStart() {
        if (customIpsInput.text?.toString().orEmpty().isNotBlank() && !applyManualIps(true)) return
        val info = NetEnv.detect(this)
        if (info.vpnActive) showConfirm("检测到 VPN。结果将代表 VPN 出口网络，是否继续？") { launchRun(info) } else launchRun(info)
    }

    /** Replaces the active run before cancelling its resources, so stale cleanup cannot own the new run. */
    private fun beginRunLease(): RunLease {
        val lease = RunLease(runGeneration.incrementAndGet())
        var previousJob: Job? = null
        var previousWatcher: (() -> Unit)? = null
        synchronized(runStateLock) {
            previousJob = runningJob
            previousWatcher = unregisterNetworkWatch
            activeRun = lease
            runningJob = null
            unregisterNetworkWatch = null
        }
        previousJob?.cancel()
        previousWatcher?.invoke()
        return lease
    }

    private fun isCurrentRun(lease: RunLease): Boolean = synchronized(runStateLock) {
        activeRun === lease && runGeneration.get() == lease.generation
    }

    private fun attachRunWatcher(lease: RunLease, watcher: () -> Unit) {
        val accepted = synchronized(runStateLock) {
            if (activeRun === lease && runGeneration.get() == lease.generation) {
                lease.unregisterWatcher = watcher
                unregisterNetworkWatch = watcher
                true
            } else false
        }
        if (!accepted) watcher()
    }

    private fun attachRunJob(lease: RunLease, job: Job) {
        val accepted = synchronized(runStateLock) {
            if (activeRun === lease && runGeneration.get() == lease.generation) {
                lease.job = job
                runningJob = job
                true
            } else false
        }
        if (!accepted) job.cancel()
    }

    private fun cancelRunIfCurrent(lease: RunLease) {
        val job = synchronized(runStateLock) {
            if (activeRun === lease && runGeneration.get() == lease.generation) lease.job else null
        }
        job?.cancel()
    }

    private fun cancelActiveRun(abandonOutcome: Boolean = false) {
        var job: Job? = null
        var watcher: (() -> Unit)? = null
        synchronized(runStateLock) {
            val lease = activeRun?.takeIf { runGeneration.get() == it.generation }
            job = lease?.job
            if (abandonOutcome) {
                // Back means the user has left this run. Invalidate even when
                // the worker just released its lease but its UI post is queued.
                runGeneration.incrementAndGet()
                activeRun = null
                runningJob = null
                watcher = lease?.unregisterWatcher ?: unregisterNetworkWatch
                lease?.unregisterWatcher = null
                unregisterNetworkWatch = null
            }
        }
        job?.cancel()
        watcher?.invoke()
    }

    /** Releases only this exact run. Returns false when a newer run already owns the UI/resources. */
    private fun releaseRun(lease: RunLease): Boolean {
        var watcher: (() -> Unit)? = null
        val released = synchronized(runStateLock) {
            if (activeRun !== lease || runGeneration.get() != lease.generation) {
                false
            } else {
                activeRun = null
                watcher = lease.unregisterWatcher
                lease.unregisterWatcher = null
                if (unregisterNetworkWatch === watcher) unregisterNetworkWatch = null
                if (runningJob === lease.job) runningJob = null
                true
            }
        }
        if (released) watcher?.invoke()
        return released
    }

    /** A completed run may publish its final page only if no newer generation has started. */
    private fun isLatestRunOutcome(lease: RunLease): Boolean = synchronized(runStateLock) {
        activeRun == null && runGeneration.get() == lease.generation
    }

    private fun launchRun(network: NetEnv.NetInfo) {
        val expectedMbps = expectedBandwidthInput.text?.toString()?.trim()?.toIntOrNull()
        if (expectedMbps == null || expectedMbps !in 1..2_000) {
            Toast.makeText(this, "期望带宽请填写 1–2000 Mbps", Toast.LENGTH_LONG).show(); return
        }
        val host = if (advancedValidation) try {
            AuthorizedHost.normalizeHost(testHost.text?.toString().orEmpty())
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Argo 域名无效", Toast.LENGTH_LONG).show(); return
        } else ""
        val wsPath = if (advancedValidation) try {
            AuthorizedHost.normalizeWsPath(wsPathInput.text?.toString().orEmpty())
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "WS Path 无效", Toast.LENGTH_LONG).show(); return
        } else ""
        val parsedArgoPort = if (advancedValidation) argoPortInput.text?.toString()?.trim()?.toIntOrNull() else 443
        if (advancedValidation && parsedArgoPort !in setOf(443, 2053, 2083, 2087, 2096, 8443)) {
            Toast.makeText(this, "高级复核仅支持 Cloudflare HTTPS 端口：443/2053/2083/2087/2096/8443", Toast.LENGTH_LONG).show(); return
        }
        val argoPort = parsedArgoPort ?: 443
        val families = when (protocol) { "IPv4" -> listOf("IPv4"); "IPv6" -> listOf("IPv6"); else -> listOf("IPv4", "IPv6") }.filterNot { it == "IPv6" && !network.ipv6Available }
        if (families.isEmpty()) { Toast.makeText(this, "所选协议族没有可用链路", Toast.LENGTH_LONG).show(); return }
        val params = when (strategy) {
            "亚洲狩猎" -> IpPipeline.ASIA_HUNT
            "最大带宽" -> IpPipeline.MAX_BANDWIDTH
            else -> IpPipeline.BALANCED
        }
        val lease = beginRunLease()
        logQueue.clear(); logLines.clear(); logs.text = ""; progress.progress = 0; percent.text = "0%"; switchTo(run, "run")
        val networkChanged = AtomicBoolean(false)
        val watcher = NetEnv.watchChanges(this, NetEnv.fingerprint(this)) { before, after ->
            networkChanged.set(true)
            if (isCurrentRun(lease)) {
                appendLog("!! 网络变化（$before → $after），已取消")
                cancelRunIfCurrent(lease)
            }
        }
        attachRunWatcher(lease, watcher)
        val job = scope.launch {
            try {
                appendRunLog(lease, "=== $strategy / ${families.joinToString("+")} / ${effectiveOperator(network)} ===")
                appendRunLog(lease, "主模式：公开测速主机 ${ProbeEngine.SPEED_HOST}:443 · 目标 ${expectedMbps} Mbps")
                if (advancedValidation) appendRunLog(lease, "高级复核：$host:$argoPort；WS Path=${wsPath.ifBlank { "未填写" }}")
                setStage(lease, "刷新 Cloudflare 网段"); CfRanges.refresh()
                if (!isCurrentRun(lease)) return@launch
                appendRunLog(lease, "Cloudflare 网段：IPv4=${if (CfRanges.v4FromOnline) "在线" else "内置备用"} / IPv6=${if (CfRanges.v6FromOnline) "在线" else "内置备用"}")
                setStage(lease, "获取公开测速 DNS 种子")
                val speedSnapshot = try {
                    AuthorizedHost.snapshot(ProbeEngine.SPEED_HOST) { appendRunLog(lease, it.replace("授权 DNS", "测速域名 DNS")) }
                } catch (e: Exception) {
                    appendRunLog(lease, "公开测速 DNS 种子获取失败，继续使用 CF 官方网段抽样")
                    AuthorizedHostSnapshot(ProbeEngine.SPEED_HOST, emptyList(), emptyList())
                }
                if (!isCurrentRun(lease)) return@launch
                if (advancedValidation) {
                    setStage(lease, "检查高级复核域名")
                    AuthorizedHost.snapshot(host) { appendRunLog(lease, it) }
                }
                if (!isCurrentRun(lease)) return@launch
                val all = LinkedHashMap<String, List<IpMetric>>(); val asia = LinkedHashMap<String, List<IpMetric>>(); val popCounts = LinkedHashMap<String, Map<String, Int>>()
                var invalid = false
                families.forEachIndexed { idx, family ->
                    if (!isCurrentRun(lease)) return@launch
                    val candidates = selectCandidates(speedSnapshot, family, lease)
                    if (candidates.isEmpty()) { appendRunLog(lease, "$family 无安全候选，跳过"); all[family] = emptyList(); asia[family] = emptyList(); popCounts[family] = emptyMap(); return@forEachIndexed }
                    appendRunLog(lease, "$family 候选 ${candidates.size}，最大预计流量 ≈ ${"%.1f".format(IpPipeline.estimateTrafficUpperBoundMb(candidates.size, params, expectedMbps))} MB")
                    val runResult = IpPipeline.runFamily(
                        argoHost = host,
                        wsPath = wsPath,
                        family = family,
                        candidates = candidates,
                        params = params,
                        asiaHunt = strategy == "亚洲狩猎",
                        argoPort = argoPort,
                        expectedMbps = expectedMbps,
                        networkInvalid = { networkChanged.get() },
                        onStage = { state ->
                            val span = 92 / families.size
                            val f = if (state.total == 0) 0.0 else state.current.toDouble() / state.total
                            setStage(lease, "$family · ${state.name}${if (state.total > 0) " ${state.current}/${state.total}" else ""}")
                            updateProgress(lease, idx * span + (f * span).toInt())
                        },
                        log = { appendRunLog(lease, "  $it") }
                    )
                    if (!isCurrentRun(lease)) return@launch
                    invalid = invalid || runResult.invalid; all[family] = runResult.ranked; asia[family] = runResult.asiaRanked; popCounts[family] = runResult.popCounts
                }
                if (!isCurrentRun(lease)) return@launch
                invalid = invalid || networkChanged.get(); updateProgress(lease, 100); setStage(lease, "完成"); appendRunLog(lease, if (invalid) "=== 网络变化，本轮仅供参考 ===" else "=== 完成 ===")
                saveHistory(all, families, invalid, host, wsPath, argoPort, expectedMbps)
                if (releaseRun(lease)) postUiIfAlive {
                    if (isLatestRunOutcome(lease) && currentPage == "run") {
                        showResults(all, asia, popCounts, families, invalid, host, wsPath, argoPort, expectedMbps)
                    }
                }
            } catch (e: CancellationException) {
                appendRunLog(lease, "=== 已停止 ===")
                if (releaseRun(lease)) {
                    postUiIfAlive {
                        if (isLatestRunOutcome(lease) && currentPage == "run") {
                            switchTo(home, "home"); refreshStatus()
                            Toast.makeText(this@MainActivity, "测速已停止", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                throw e
            } catch (e: Exception) {
                appendRunLog(lease, "!! ${e.message ?: e.javaClass.simpleName}")
                if (releaseRun(lease)) {
                    postUiIfAlive {
                        if (isLatestRunOutcome(lease) && currentPage == "run") {
                            switchTo(home, "home"); refreshStatus()
                            Toast.makeText(this@MainActivity, e.message ?: "测试失败", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        attachRunJob(lease, job)
        if (networkChanged.get()) cancelRunIfCurrent(lease)
    }
    private fun selectCandidates(snapshot: AuthorizedHostSnapshot, family: String, lease: RunLease): List<IpPipeline.Candidate> =
        CandidatePool.build(snapshot, importedIps, family, includeOfficialSamples = true, snapshotSource = "测速域名DNS").also {
            appendRunLog(lease, "$family 候选 ${it.candidates.size}；导入有效 ${it.acceptedImported}/${it.importedCount}" +
                "；拒绝非 CF ${it.ignoredOutsideCloudflare}；跨协议族 ${it.ignoredWrongFamily}${if (it.importedSampled) "；长列表已分散抽样" else ""}")
        }.candidates.map { IpPipeline.Candidate(it.ip, it.source) }
    private fun appendRunLog(lease: RunLease, value: String) { if (isCurrentRun(lease)) appendLog(value) }
    private fun appendLog(value: String) {
        logQueue.add(value); if (flushing.compareAndSet(false, true)) logHandler.postDelayed({ flushLogs() }, 180)
    }
    private fun flushLogs() {
        flushing.set(false); repeat(160) { val line = logQueue.poll() ?: return@repeat; logLines.addLast(line) }
        while (logLines.size > 180) logLines.removeFirst(); if (::logs.isInitialized) logs.text = logLines.joinToString("\n")
        if (logQueue.isNotEmpty() && flushing.compareAndSet(false, true)) logHandler.postDelayed({ flushLogs() }, 180)
    }
    private fun setStage(lease: RunLease, value: String) = runOnUiThread {
        if (isCurrentRun(lease) && ::stage.isInitialized) stage.text = value
    }
    private fun updateProgress(lease: RunLease, value: Int) = runOnUiThread {
        if (!isCurrentRun(lease)) return@runOnUiThread
        val safe = value.coerceIn(0, 100); progress.progress = safe; percent.text = "$safe%"
    }

    // ------------------------------------------ results / history
    private fun buildResult() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(30), dp(20), dp(20)); setBackgroundColor(bg) }
        result = root; root.addView(label("结果 · IP 原生排名", 22f, primary, true))
        root.addView(label("点击复制裸 IP，只替换 VMess / VLESS 节点的 address/server。原端口、UUID、TLS SNI、WS Host/Path 全部保持不变。", 11.5f, muted).apply { setPadding(0, dp(4), 0, dp(10)) })
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; root.addView(ScrollView(this).apply { addView(results) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(primaryButton("返回首页") { switchTo(home, "home"); refreshStatus() }, lp(12))
    }
    private fun showResults(all: Map<String, List<IpMetric>>, asia: Map<String, List<IpMetric>>, pops: Map<String, Map<String, Int>>, families: List<String>, invalid: Boolean, argoHost: String, wsPath: String, argoPort: Int, expectedMbps: Int) {
        results.removeAllViews(); if (invalid) results.addView(label("⚠ 网络在测试中变化，结果仅供参考。", 13f, warn, true))
        results.addView(panel {
            addView(label("使用方法", 13f, primary, true))
            addView(label("address/server = 推荐 IP\n其他节点参数 = 保持原样\n本轮期望带宽 = $expectedMbps Mbps", 12f, secondary).apply { setPadding(0, dp(6), 0, 0) })
            if (argoHost.isNotBlank()) addView(label("已启用高级复核：$argoHost:$argoPort · Path ${wsPath.ifBlank { "未填写" }}", 11f, good).apply { setPadding(0, dp(6), 0, 0) })
        }, lp(8))
        families.forEach { family ->
            val ranked = all[family].orEmpty()
            val usable = ranked.filter { it.isNodeUsable }
            val rejected = ranked.filterNot { it.isNodeUsable }
            results.addView(label(when (strategy) { "亚洲狩猎" -> "$family · 亚洲狩猎"; "最大带宽" -> "$family · 最大带宽"; else -> "$family · 均衡排名" }, 17f, accent, true).apply { setPadding(0, dp(18), 0, dp(7)) })
            if (strategy == "亚洲狩猎") {
                results.addView(label("公开 trace：" + listOf("HKG", "NRT", "SIN", "ICN", "TPE").joinToString(" · ") { "$it ${pops[family]?.get(it) ?: 0}" }, 12f, secondary))
                addMetricSection("推荐榜（稳定速度优先）", asia[family].orEmpty().filter { it.isNodeUsable }.take(20), !invalid, expectedMbps)
            } else addMetricSection("可直接填入节点的 IP", usable.take(20), !invalid, expectedMbps)
            if (rejected.isNotEmpty()) addMetricSection("未复测 / 未通过（仅诊断）", rejected.take(12), false, expectedMbps)
        }; switchTo(result, "result")
    }
    private fun addMetricSection(title: String, metrics: List<IpMetric>, allowCopy: Boolean, expectedMbps: Int) {
        results.addView(label(title, 14f, primary, true).apply { setPadding(0, dp(14), 0, dp(6)) })
        val dnsChampionIndex = if (allowCopy) metrics.indexOfFirst { it.isDnsSyncEligible } else -1
        if (metrics.isEmpty()) results.addView(label("（没有通过两次真实下载复测的结果）", 12f, muted)) else metrics.forEachIndexed { index, metric ->
            results.addView(
                metricCard(
                    index,
                    metric,
                    allowCopy && metric.isNodeUsable,
                    expectedMbps,
                    allowDns = index == dnsChampionIndex
                ),
                lp(if (index == 0) 0 else 8)
            )
        }
    }
    @SuppressLint("SetTextI18n")
    private fun metricCard(index: Int, metric: IpMetric, allowCopy: Boolean, expectedMbps: Int, allowDns: Boolean): View = panel {
        background = shape(if (index < 3) cardTop else card, 16, stroke)
        val medal = when (index) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "${index + 1}" }
        addView(label("$medal  ${metric.ip}${if (allowCopy) "  ⧉" else ""}", 15f, primary, true).apply {
            if (allowCopy) setOnClickListener { copy(metric.ip, "IP") }
        })
        addView(label("${metric.family} · ${metric.source} · 1 秒下载样本 ${metric.full.size} 次", 11f, muted))
        val route = metric.route
        if (metric.routeValidationRequired) {
            if (route?.ok == true) {
                addView(label("高级节点复核：TLS/SNI/Host ✓ · trace HTTP ${route.traceHttpCode}" +
                    (if (route.wsPath.isNotEmpty()) " · WS 101 ✓" else "") , 11f, good, true))
            } else addView(label("高级节点复核失败：${route?.error ?: "未执行"}", 11f, bad))
        } else addView(label("直接 IP 模式 · ${ProbeEngine.SPEED_HOST}:443 TLS 严格校验", 11f, good))
        addView(label("平均 ${fmt(metric.avgCompleteMbps)} Mbps · 最低 ${fmt(metric.minCompleteMbps)} Mbps · 可靠下限 ${fmt(metric.floorMbps)} Mbps", 12f, secondary).apply { setPadding(0, dp(5), 0, 0) })
        val targetMet = metric.floorMbps >= expectedMbps
        addView(label(if (targetMet) "达标 ✓ 可靠下限已达 $expectedMbps Mbps" else "未达目标：可靠下限低于 $expectedMbps Mbps", 11f, if (targetMet) good else warn, targetMet))
        addView(label("成功率 ${fmt(metric.fullSuccessRatePct)}% · 波动 ${fmt(metric.variationPct)}% · TTFB ${if (metric.medianTtfbMs < 0) "n/a" else "${fmt(metric.medianTtfbMs)} ms"} · ${metric.stability}", 11f, secondary))
        if (metric.primaryPop.isNotBlank()) addView(label("入口：${metric.primaryPop} · 亚洲评分 ${metric.edgeScore}${if (metric.popDrift) " · POP 漂移" else ""}", 11f, if (metric.edgeScore > 0) good else secondary, metric.edgeScore > 0))
        when {
            metric.pre?.ok == false -> addView(label("预检失败；未进入 Full，已按 0 计入。", 10.5f, warn))
            metric.micro?.ok == false -> addView(label("1 秒下载测速失败；未进入复测。", 10.5f, warn))
            metric.full.isEmpty() -> addView(label("未进入 1 秒真实下载测速。", 10.5f, warn))
            metric.full.any { !it.ok } -> addView(label("复测含失败样本，不作为可用节点推荐。", 10.5f, warn))
        }
        if (allowCopy) {
            addView(primaryButton("复制到节点地址 / server") { copy(metric.ip, "IP") }, lp(8))
            if (allowDns) addView(secondaryButton("解析到我的域名（DNS-only）") { showDnsSyncDialog(metric.ip) }, lp(8))
        } else addView(label("此项不可作为节点地址复制。", 10.5f, warn).apply { setPadding(0, dp(8), 0, 0) })
    }
    private fun fmt(value: Double) = "%.1f".format(value)
    private fun saveHistory(all: Map<String, List<IpMetric>>, families: List<String>, invalid: Boolean, argoHost: String, wsPath: String, argoPort: Int, expectedMbps: Int) {
        try {
            val list = if (invalid) emptyList() else IpPipeline.rank(all.values.flatten().filter { it.isNodeUsable })
            val winner = list.firstOrNull(); val net = NetEnv.detect(this)
            val target = winner?.floorMbps?.let { it >= expectedMbps } == true
            val mode = if (argoHost.isBlank()) "直接 IP 模式" else "高级复核 $argoHost:$argoPort · Path ${wsPath.ifBlank { "未填写" }}"
            val verdict = "$mode · 目标 $expectedMbps Mbps · " + when {
                invalid -> "网络变化，结果不可用于节点"
                winner == null -> "未找到可用 IP"
                target -> "冠军可靠下限达标"
                else -> "冠军可用，但可靠下限未达目标"
            }
            HistoryStore.save(filesDir, HistoryStore.HistoryEntry(System.currentTimeMillis(), System.currentTimeMillis(), strategy, families.joinToString("+"), net.label, net.vpnActive, invalid, net.wifiSsid, effectiveOperator(net), net.phoneModel, winner?.ip.orEmpty(), winner?.let { fmt(it.avgCompleteMbps) }.orEmpty(), verdict, list.take(50).mapIndexed { i, m ->
                HistoryStore.ResultLine(i + 1, m.ip, fmt(m.avgCompleteMbps), fmt(m.minCompleteMbps), fmt(m.floorMbps), "${fmt(m.fullSuccessRatePct)}%", "${fmt(m.variationPct)}%", if (m.medianTtfbMs < 0) "" else fmt(m.medianTtfbMs), m.stability, false, m.primaryPop)
            }))
        } catch (_: Exception) { appendLog("历史保存失败") }
    }
    @SuppressLint("SimpleDateFormat")
    private fun showHistory() {
        val entries = HistoryStore.loadAll(filesDir); val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(30), dp(20), dp(20)); setBackgroundColor(bg) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("历史测试", 22f, primary, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (entries.isNotEmpty()) head.addView(secondaryButton("清空") { showConfirm("清空全部历史记录？") { HistoryStore.clearAll(filesDir); showHistory() } }); root.addView(head)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (entries.isEmpty()) list.addView(label("还没有测试记录。", 13f, muted)) else entries.forEach { entry -> list.addView(panel {
            val time = java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(entry.ts))
            addView(label("$time · ${entry.modeLabel} · ${entry.families}${if (entry.invalid) " · 无效" else ""}", 13f, primary, true)); addView(label("冠军 IP：${entry.champ.ifBlank { "无" }} · ${entry.champMbps} Mbps", 12f, secondary)); addView(label(entry.verdict, 11f, muted))
            setOnClickListener { showHistoryDetail(entry) }; setOnLongClickListener { showConfirm("删除这条历史记录？") { HistoryStore.delete(filesDir, entry.id); showHistory() }; true }
        }, lp(8)) }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)); root.addView(primaryButton("返回首页") { switchTo(home, "home"); refreshStatus() }, lp(12)); switchTo(root, "history")
    }
    private fun showHistoryDetail(entry: HistoryStore.HistoryEntry) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(30), dp(20), dp(20)); setBackgroundColor(bg) }
        root.addView(label("历史结果 · ${entry.champ.ifBlank { "无冠军" }}", 20f, primary, true)); root.addView(label(entry.verdict, 12f, muted).apply { setPadding(0, dp(5), 0, dp(10)) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; entry.results.forEach { r -> list.addView(panel {
            addView(label("#${r.rank}  ${r.ip}  ⧉", 14f, primary, true).apply { setOnClickListener { copy(r.ip, "IP") } }); addView(label("平均 ${r.avg} Mbps · 最低 ${r.min} Mbps · 下限 ${r.floor} Mbps", 12f, secondary)); addView(label("成功率 ${r.sr} · 波动 ${r.variation} · TTFB ${r.ttfb} ms · ${r.stability} ${r.pops}", 11f, muted))
        }, lp(8)) }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)); root.addView(primaryButton("返回历史") { showHistory() }, lp(12)); switchTo(root, "history_detail")
    }

    /** Two-phase Cloudflare A/AAAA synchronization; never logs credentials. */
    private fun showDnsSyncDialog(championIp: String) {
        val dialog = Dialog(this)
        // Cancel only abandons the read-only preview flow. It never claims to
        // cancel a request that has already been sent.
        val previewFinishedOrAbandoned = AtomicBoolean(false)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(16))
            background = shape(card, 18, stroke)
        }
        val type = if (championIp.contains(':')) "AAAA" else "A"
        root.addView(label("解析冠军 IP 到我的域名", 17f, primary, true))
        root.addView(label("将使用 $type 灰云记录（DNS-only）指向 $championIp。这里的 DNS 记录名与节点 TLS SNI / WS Host 是两个独立字段。", 11.5f, secondary).apply { setPadding(0, dp(7), 0, dp(12)) })

        val prefs = getSharedPreferences("cf_dns_ui", MODE_PRIVATE)
        val zoneInput = input("Zone ID（32 位，必填）").apply {
            setSingleLine(true); setText(prefs.getString("zone_id", "").orEmpty())
        }
        val recordInput = input("完整 DNS 记录名，例如 edge.example.com").apply {
            setSingleLine(true); setText(prefs.getString("record_name", "").orEmpty())
        }
        val storedToken = sessionCloudflareToken ?: CloudflareTokenStore.load(this)
        if (sessionCloudflareToken == null && storedToken != null) sessionCloudflareToken = storedToken
        val tokenInput = input(
            if (storedToken == null) "Cloudflare API Token" else "已有本机/会话 Token，留空即使用",
            password = true
        ).apply { setSingleLine(true) }
        root.addView(zoneInput)
        root.addView(recordInput, lp(8))
        root.addView(tokenInput, lp(8))
        root.addView(label("最小权限：仅授权目标 Zone，Zone / DNS / Edit。不支持 Global API Key，不会自动查找 Zone。", 11f, warn).apply { setPadding(0, dp(7), 0, 0) })
        val remember = CheckBox(this).apply {
            text = "在本机安全保存 Token（默认关闭）"
            setTextColor(secondary); textSize = 11.5f; isChecked = false
            buttonTintList = ColorStateList.valueOf(accent)
        }
        root.addView(remember, lp(8))
        root.addView(label(CloudflareTokenStore.SECURITY_NOTICE, 10.5f, muted))
        if (storedToken != null) root.addView(secondaryButton("清除本机与当前会话 Token") {
            CloudflareTokenStore.clear(this)
            sessionCloudflareToken = null
            tokenInput.hint = "Cloudflare API Token"
            Toast.makeText(this, "已清除保存的 Token", Toast.LENGTH_SHORT).show()
        }, lp(8))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cancelPreviewButton = secondaryButton("取消") {
            previewFinishedOrAbandoned.set(true)
            dialog.dismiss()
        }
        row.addView(cancelPreviewButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        val previewButton = primaryButton("生成只读预览") {}
        row.addView(previewButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(row, lp(14))
        previewButton.setOnClickListener {
            val zoneId = zoneInput.text?.toString().orEmpty()
            val recordName = recordInput.text?.toString().orEmpty()
            val typed = tokenInput.text?.toString().orEmpty()
            val token = try {
                if (typed.isNotBlank()) CloudflareApiToken.parse(typed) else sessionCloudflareToken
                    ?: throw IllegalArgumentException("请填写 Cloudflare API Token")
            } catch (e: Exception) {
                Toast.makeText(this, e.message ?: "API Token 无效", Toast.LENGTH_LONG).show(); return@setOnClickListener
            }
            val config = try {
                CloudflareDns.normalizeConfig(CloudflareDns.Config(zoneId, recordName, token))
            } catch (e: Exception) {
                Toast.makeText(this, e.message ?: "DNS 配置无效", Toast.LENGTH_LONG).show(); return@setOnClickListener
            }
            previewButton.isEnabled = false
            previewButton.text = "正在读取记录…"
            val rememberToken = remember.isChecked
            tokenInput.setText("")
            scope.launch {
                try {
                    val plan = CloudflareDns.inspect(config, championIp)
                    postUiIfAlive {
                        // Cancellation/back/outside-tap wins if it happened
                        // before this UI hand-off; no later confirm may appear.
                        if (!dialog.isShowing ||
                            !previewFinishedOrAbandoned.compareAndSet(false, true)
                        ) return@postUiIfAlive
                        dialog.dismiss()
                        sessionCloudflareToken = token
                        prefs.edit()
                            .putString("zone_id", config.zoneId)
                            .putString("record_name", config.recordName)
                            .apply()
                        if (rememberToken) scope.launch {
                            try {
                                CloudflareTokenStore.save(this@MainActivity, token)
                            } catch (_: Exception) {
                                postUiIfAlive {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Token 安全保存失败；本次仅在当前会话使用",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                        showDnsPlanConfirm(config, plan)
                    }
                } catch (e: Exception) {
                    postUiIfAlive {
                        if (previewFinishedOrAbandoned.get() || !dialog.isShowing) {
                            return@postUiIfAlive
                        }
                        previewButton.isEnabled = true
                        previewButton.text = "生成只读预览"
                        Toast.makeText(this@MainActivity, e.message ?: "DNS 预览失败", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.setOnCancelListener { previewFinishedOrAbandoned.set(true) }
        dialog.setContentView(ScrollView(this).apply { addView(root) })
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun showDnsPlanConfirm(config: CloudflareDns.Config, plan: CloudflareDns.SyncPlan) {
        val dialog = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(16))
            background = shape(card, 18, stroke)
        }
        root.addView(label(if (plan.requiresWrite) "Cloudflare DNS 写入确认" else "DNS 无需更新", 17f, primary, true))
        root.addView(label(plan.confirmationText, 13f, primary, true).apply { setPadding(0, dp(10), 0, 0) })
        root.addView(label(
            if (plan.requiresWrite) "只会创建或更新上面这一条 ${plan.type.name} 记录；强制灰云 DNS-only，TTL 自动。同名 CNAME / NS 冲突会直接拒绝，不会删除或转换。"
            else "当前 ${plan.type.name} 的 IP、灰云 DNS-only 和 TTL 自动状态均已一致；本次只读检查没有发送任何写入。",
            11.5f,
            if (plan.requiresWrite) warn else good
        ).apply { setPadding(0, dp(8), 0, dp(12)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (!plan.requiresWrite) {
            row.addView(primaryButton("完成") { dialog.dismiss() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
            root.addView(row)
            dialog.setContentView(root)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.show()
            return
        }
        val writeNotice = label("", 10.5f, warn, true).apply { visibility = View.GONE }
        root.addView(writeNotice, lp(8))
        val cancelWriteButton = secondaryButton("取消") { dialog.dismiss() }
        row.addView(cancelWriteButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        val applyButton = primaryButton("确认写入") {}
        row.addView(applyButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(row)
        applyButton.setOnClickListener {
            // From this point the write and mandatory read-back verification
            // form one indivisible UI operation. We do not offer a misleading
            // cancel action for an HTTP request that may already be in flight.
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            cancelWriteButton.isEnabled = false
            cancelWriteButton.text = "写入中"
            applyButton.isEnabled = false
            applyButton.text = "写入并回读校验…"
            writeNotice.text = "请求已确认并开始写入；写入完成前请勿关闭。"
            writeNotice.visibility = View.VISIBLE
            scope.launch {
                try {
                    val synced = CloudflareDns.apply(config, plan)
                    postUiIfAlive {
                        dialog.dismiss()
                        Toast.makeText(
                            this@MainActivity,
                            "${synced.type.name} ${synced.name} 已同步为 ${synced.content}（DNS-only）",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) {
                    postUiIfAlive {
                        dialog.setCancelable(true)
                        dialog.setCanceledOnTouchOutside(true)
                        cancelWriteButton.isEnabled = true
                        cancelWriteButton.text = "取消"
                        applyButton.isEnabled = true
                        applyButton.text = "确认写入"
                        writeNotice.text = "本次操作未通过写入与回读校验；请按提示处理后重试或关闭。"
                        Toast.makeText(this@MainActivity, e.message ?: "DNS 写入失败", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    /** Executes UI work only while this Activity can still own that UI. */
    private fun postUiIfAlive(block: () -> Unit) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (!isFinishing && !isDestroyed) block()
        }
    }

    private fun copy(value: String, kind: String) { try { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(kind, value)); Toast.makeText(this, "已复制：$value", Toast.LENGTH_SHORT).show() } catch (_: Exception) { Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show() } }
    private fun showConfirm(message: String, onConfirm: () -> Unit) {
        val dialog = Dialog(this); val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(20), dp(22), dp(16)); background = shape(card, 18, stroke) }; root.addView(label(message, 14f, primary).apply { setPadding(0, 0, 0, dp(16)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; row.addView(secondaryButton("取消") { dialog.dismiss() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) }); row.addView(primaryButton("继续") { dialog.dismiss(); onConfirm() }, LinearLayout.LayoutParams(0, dp(44), 1f)); root.addView(row); dialog.setContentView(root); dialog.window?.setBackgroundDrawableResource(android.R.color.transparent); dialog.show()
    }
    private fun switchTo(view: View, page: String) { currentPage = page; setContentView(view) }
    override fun onBackPressed() { when (currentPage) { "run" -> { cancelActiveRun(abandonOutcome = true); switchTo(home, "home"); refreshStatus() }; "result", "history" -> { switchTo(home, "home"); refreshStatus() }; "history_detail" -> showHistory(); else -> super.onBackPressed() } }
    override fun onDestroy() {
        var watcher: (() -> Unit)? = null
        synchronized(runStateLock) {
            runGeneration.incrementAndGet()
            activeRun = null
            runningJob = null
            watcher = unregisterNetworkWatch
            unregisterNetworkWatch = null
        }
        watcher?.invoke()
        logHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }
}

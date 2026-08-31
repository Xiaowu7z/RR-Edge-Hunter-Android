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

/**
 * CF 优选IP ranks Cloudflare entry addresses for an existing Argo node. The
 * selected IP is used only as the node address/server; TLS SNI, HTTP Host and
 * certificate verification always remain bound to the user's Argo hostname.
 */
class MainActivity : Activity() {
    companion object { private const val REQUEST_OPEN_IP_FILE = 711 }

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
    private var runningJob: Job? = null
    private var currentPage = "home"
    private var unregisterNetworkWatch: (() -> Unit)? = null
    private var protocol = "双栈"
    private var strategy = "均衡"
    private var operatorLabel = "自动"
    private var source = "Argo优选"
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
        root.addView(label("为现有 Argo 节点寻找更稳定的 Cloudflare 入口 IP。IP 填入节点地址/server，原域名继续作为 SNI 与 Host。", 12f, secondary).apply { setPadding(0, dp(14), 0, dp(8)) })
        status = label("", 12f, muted); root.addView(status)

        root.addPanel(12) {
            addView(heading("Argo 节点 · 必填"))
            addView(label("填写节点正在使用的 Cloudflare / Argo 域名，不要填写源站 IP、URL 或端口。", 11.5f, secondary))
            testHost = input("例如 argo.example.com").apply { setSingleLine(true) }
            addView(testHost, lp(10))
            wsPathInput = input("WS Path（可选），例如 /vless?ed=2048").apply { setSingleLine(true) }
            addView(wsPathInput, lp(8))
            addView(label("留空时只验证 TLS、SNI、Host 与 Cloudflare 入口；填写后会额外执行严格 WebSocket 101 握手。", 11f, muted).apply { setPadding(0, dp(7), 0, 0) })
            addView(segmented(listOf("Argo优选", "当前DNS"), listOf("一键优选（推荐）", "当前 DNS 体检"), "Argo优选") {
                source = it; customPanel.visibility = View.GONE; refreshStatus()
            }, lp(12))
            addView(label("一键优选会组合当前 DNS、CF 官方网段受控抽样与已导入 IP 池；当前 DNS 体检只检查域名现有入口。", 11f, warn).apply { setPadding(0, dp(7), 0, 0) })
            addView(label("点击开始即表示你确认有权使用并测试该 Argo 节点。", 10.5f, muted).apply { setPadding(0, dp(7), 0, 0) })
        }

        root.addPanel(12) {
            addView(heading("协议族与测速策略"))
            protocolSummary = label("", 12f, secondary); addView(protocolSummary)
            addView(segmented(listOf("IPv4", "IPv6", "双栈"), initial = "双栈") { protocol = it; refreshStatus() })
            addView(heading("测速策略").apply { setPadding(0, dp(16), 0, dp(8)) })
            addView(segmented(listOf("均衡", "亚洲狩猎"), initial = "均衡") { strategy = it; refreshStatus() })
            addView(label("亚洲狩猎仍以成功率与稳定速度为主，POP 只在同档成绩中加分，不会让慢速 HKG 压过高速入口。", 11f, muted).apply { setPadding(0, dp(6), 0, 0) })
            addView(heading("线路标签").apply { setPadding(0, dp(16), 0, dp(8)) })
            addView(segmented(listOf("自动", "中国移动", "中国电信", "中国联通"), listOf("自动", "移动", "电信", "联通"), "自动") { operatorLabel = it; refreshStatus() })
            addView(label("标签用于历史和对比；不会模拟运营商网络或改变 IP 池。", 11f, muted).apply { setPadding(0, dp(6), 0, 0) })
        }

        root.addPanel(12) {
            addView(heading("自定义 IP 池 · 可选"))
            addView(label("无需导入即可测试。也可长复制、上传文件或订阅社区 IP 池；只有 Cloudflare 官方 CIDR 内的地址会进入候选。", 11.5f, muted))
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

        root.addPanel(12) {
            addView(heading("安全边界"))
            addView(label("• 候选仅限 Cloudflare 官方网段，并受总量、并发和流量上限约束。", 12f, secondary))
            addView(label("• 每个候选都固定 IP 连接，但证书、TLS SNI 与 HTTP Host 始终使用你的 Argo 域名。", 12f, secondary))
            addView(label("• 工具只输出节点填写参数，不改 DNS、hosts、路由或系统代理。", 12f, secondary))
        }
        root.addView(primaryButton("一键寻找 Argo 最优入口") { preflightAndStart() }, lp(16))
        root.addView(secondaryButton("历史记录") { showHistory() }, lp(8))
    }

    private fun effectiveOperator(info: NetEnv.NetInfo) = if (operatorLabel == "自动") info.carrier.ifBlank { "自动" } else operatorLabel
    private fun refreshStatus() {
        if (!::status.isInitialized) return
        val info = NetEnv.detect(this)
        val sourceText = if (source == "当前DNS") "当前 DNS 体检" else "自动池${if (importedIps.isNotEmpty()) " + 导入 ${importedIps.size}" else ""}"
        status.text = "${info.label} · 线路：${effectiveOperator(info)} · 候选：$sourceText"
        protocolSummary.text = "$protocol · $strategy"
    }
    private fun refreshImportStatus(error: String? = null) {
        if (!::importStatus.isInitialized) return
        when {
            error != null -> { importStatus.text = error; importStatus.setTextColor(bad) }
            importedIps.isEmpty() -> { importStatus.text = "尚未导入；一键优选仍会使用当前 DNS 与 CF 官方受控抽样。"; importStatus.setTextColor(muted) }
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
        // Keep the visible source selector authoritative.  An asynchronous file
        // picker may return after the user has switched back to current DNS; in
        // that case the import remains available but must not silently change
        // the active test mode behind the selected UI state.
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
        root.addView(secondaryButton("停止本次测试") { runningJob?.cancel() }, lp(14))
    }
    private fun preflightAndStart() {
        if (source == "Argo优选" && customIpsInput.text?.toString().orEmpty().isNotBlank() && !applyManualIps(true)) return
        val info = NetEnv.detect(this)
        if (info.vpnActive) showConfirm("检测到 VPN。结果将代表 VPN 出口网络，是否继续？") { launchRun(info) } else launchRun(info)
    }
    private fun launchRun(network: NetEnv.NetInfo) {
        val host = try { AuthorizedHost.normalizeHost(testHost.text?.toString().orEmpty()) } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Argo 域名无效", Toast.LENGTH_LONG).show(); return
        }
        val wsPath = try { AuthorizedHost.normalizeWsPath(wsPathInput.text?.toString().orEmpty()) } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "WS Path 无效", Toast.LENGTH_LONG).show(); return
        }
        val families = when (protocol) { "IPv4" -> listOf("IPv4"); "IPv6" -> listOf("IPv6"); else -> listOf("IPv4", "IPv6") }.filterNot { it == "IPv6" && !network.ipv6Available }
        if (families.isEmpty()) { Toast.makeText(this, "所选协议族没有可用链路", Toast.LENGTH_LONG).show(); return }
        val params = if (strategy == "亚洲狩猎") IpPipeline.ASIA_HUNT else IpPipeline.BALANCED
        runningJob?.cancel(); logQueue.clear(); logLines.clear(); logs.text = ""; progress.progress = 0; percent.text = "0%"; switchTo(run, "run")
        val networkChanged = AtomicBoolean(false)
        unregisterNetworkWatch?.invoke()
        unregisterNetworkWatch = NetEnv.watchChanges(this, NetEnv.fingerprint(this)) { before, after -> networkChanged.set(true); appendLog("!! 网络变化（$before → $after），已取消"); runningJob?.cancel() }
        runningJob = scope.launch {
            try {
                appendLog("=== $strategy / ${families.joinToString("+")} / ${effectiveOperator(network)} ===")
                appendLog("Argo 域名：$host；WS Path=${wsPath.ifBlank { "未填写" }}；模式=$source")
                setStage("刷新 Cloudflare 网段"); CfRanges.refresh()
                appendLog("Cloudflare 网段：IPv4=${if (CfRanges.v4FromOnline) "在线" else "内置备用"} / IPv6=${if (CfRanges.v6FromOnline) "在线" else "内置备用"}")
                setStage("建立授权 DNS 快照")
                val snapshot = AuthorizedHost.snapshot(host) { appendLog(it) }
                val all = LinkedHashMap<String, List<IpMetric>>(); val asia = LinkedHashMap<String, List<IpMetric>>(); val popCounts = LinkedHashMap<String, Map<String, Int>>()
                var invalid = false
                families.forEachIndexed { idx, family ->
                    val candidates = selectCandidates(snapshot, family)
                    if (candidates.isEmpty()) { appendLog("$family 无安全候选，跳过"); all[family] = emptyList(); asia[family] = emptyList(); popCounts[family] = emptyMap(); return@forEachIndexed }
                    appendLog("$family 候选 ${candidates.size}，最大预计流量 ≈ ${"%.1f".format(IpPipeline.estimateTrafficUpperBoundMb(candidates.size, params))} MB")
                    val runResult = IpPipeline.runFamily(snapshot.host, wsPath, family, candidates, params, strategy == "亚洲狩猎", { networkChanged.get() }, { state ->
                        val span = 92 / families.size; val f = if (state.total == 0) 0.0 else state.current.toDouble() / state.total
                        setStage("$family · ${state.name}${if (state.total > 0) " ${state.current}/${state.total}" else ""}"); updateProgress(idx * span + (f * span).toInt())
                    }, { appendLog("  $it") })
                    invalid = invalid || runResult.invalid; all[family] = runResult.ranked; asia[family] = runResult.asiaRanked; popCounts[family] = runResult.popCounts
                }
                invalid = invalid || networkChanged.get(); updateProgress(100); setStage("完成"); appendLog(if (invalid) "=== 网络变化，本轮仅供参考 ===" else "=== 完成 ===")
                saveHistory(all, families, invalid, host, wsPath); unregisterNetworkWatch?.invoke(); unregisterNetworkWatch = null
                runOnUiThread { showResults(all, asia, popCounts, families, invalid, host, wsPath) }
            } catch (e: CancellationException) {
                unregisterNetworkWatch?.invoke(); unregisterNetworkWatch = null; appendLog("=== 已停止 ===")
                runOnUiThread { switchTo(home, "home"); refreshStatus(); Toast.makeText(this@MainActivity, "测速已停止", Toast.LENGTH_SHORT).show() }; throw e
            } catch (e: Exception) {
                unregisterNetworkWatch?.invoke(); unregisterNetworkWatch = null; appendLog("!! ${e.message ?: e.javaClass.simpleName}")
                runOnUiThread { switchTo(home, "home"); refreshStatus(); Toast.makeText(this@MainActivity, e.message ?: "测试失败", Toast.LENGTH_LONG).show() }
            }
        }
    }
    private fun selectCandidates(snapshot: AuthorizedHostSnapshot, family: String): List<IpPipeline.Candidate> = if (source == "当前DNS") {
        snapshot.forFamily(family).map { IpPipeline.Candidate(it, "当前DNS") }
    } else {
        CandidatePool.build(snapshot, importedIps, family, includeOfficialSamples = true).also {
            appendLog("$family 自动候选 ${it.candidates.size}；导入有效 ${it.acceptedImported}/${it.importedCount}" +
                "；拒绝非 CF ${it.ignoredOutsideCloudflare}；跨协议族 ${it.ignoredWrongFamily}${if (it.importedSampled) "；长列表已分散抽样" else ""}")
        }.candidates.map { IpPipeline.Candidate(it.ip, it.source) }
    }
    private fun appendLog(value: String) {
        logQueue.add(value); if (flushing.compareAndSet(false, true)) logHandler.postDelayed({ flushLogs() }, 180)
    }
    private fun flushLogs() {
        flushing.set(false); repeat(160) { val line = logQueue.poll() ?: return@repeat; logLines.addLast(line) }
        while (logLines.size > 180) logLines.removeFirst(); if (::logs.isInitialized) logs.text = logLines.joinToString("\n")
        if (logQueue.isNotEmpty() && flushing.compareAndSet(false, true)) logHandler.postDelayed({ flushLogs() }, 180)
    }
    private fun setStage(value: String) = runOnUiThread { if (::stage.isInitialized) stage.text = value }
    private fun updateProgress(value: Int) = runOnUiThread { val safe = value.coerceIn(0, 100); progress.progress = safe; percent.text = "$safe%" }

    // ------------------------------------------ results / history
    private fun buildResult() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(30), dp(20), dp(20)); setBackgroundColor(bg) }
        result = root; root.addView(label("结果 · Argo 入口排名", 22f, primary, true))
        root.addView(label("把推荐 IP 填入节点地址/server；端口、SNI、Host 与 WS Path 保持下方配置。Full 固定 3 轮，失败按 0 Mbps 计。", 11.5f, muted).apply { setPadding(0, dp(4), 0, dp(10)) })
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; root.addView(ScrollView(this).apply { addView(results) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(primaryButton("返回首页") { switchTo(home, "home"); refreshStatus() }, lp(12))
    }
    private fun showResults(all: Map<String, List<IpMetric>>, asia: Map<String, List<IpMetric>>, pops: Map<String, Map<String, Int>>, families: List<String>, invalid: Boolean, argoHost: String, wsPath: String) {
        results.removeAllViews(); if (invalid) results.addView(label("⚠ 网络在测试中变化，结果仅供参考。", 13f, warn, true))
        results.addView(panel {
            addView(label("节点固定参数", 13f, primary, true))
            addView(label("端口：443\nSNI：$argoHost\nHost：$argoHost\nPath：${wsPath.ifBlank { "保持原节点配置" }}", 12f, secondary).apply { setPadding(0, dp(6), 0, 0) })
        }, lp(8))
        families.forEach { family ->
            val ranked = all[family].orEmpty()
            val usable = ranked.filter { it.isArgoUsable }
            val rejected = ranked.filterNot { it.isArgoUsable }
            results.addView(label(if (strategy == "亚洲狩猎") "$family · 亚洲狩猎" else "$family · 完整排名", 17f, accent, true).apply { setPadding(0, dp(18), 0, dp(7)) })
            if (strategy == "亚洲狩猎") {
                results.addView(label("Argo trace：" + listOf("HKG", "NRT", "SIN", "ICN", "TPE").joinToString(" · ") { "$it ${pops[family]?.get(it) ?: 0}" }, 12f, secondary))
                addMetricSection("推荐榜（稳定速度优先）", asia[family].orEmpty().filter { it.isArgoUsable }.take(20), argoHost, wsPath, !invalid)
                addMetricSection("全局速度榜", usable.take(20), argoHost, wsPath, !invalid)
            } else addMetricSection("可用于 Argo 的入口", usable.take(20), argoHost, wsPath, !invalid)
            if (rejected.isNotEmpty()) addMetricSection("未通过 / 未进入 Full（仅诊断）", rejected.take(12), argoHost, wsPath, false)
        }; switchTo(result, "result")
    }
    private fun addMetricSection(title: String, metrics: List<IpMetric>, argoHost: String, wsPath: String, allowCopy: Boolean) {
        results.addView(label(title, 14f, primary, true).apply { setPadding(0, dp(14), 0, dp(6)) })
        if (metrics.isEmpty()) results.addView(label("（没有通过 Argo 域名验证且完成测速的结果）", 12f, muted)) else metrics.forEachIndexed { index, metric ->
            results.addView(metricCard(index, metric, argoHost, wsPath, allowCopy && metric.isArgoUsable), lp(if (index == 0) 0 else 8))
        }
    }
    @SuppressLint("SetTextI18n")
    private fun metricCard(index: Int, metric: IpMetric, argoHost: String, wsPath: String, allowCopy: Boolean): View = panel {
        background = shape(if (index < 3) cardTop else card, 16, stroke)
        val medal = when (index) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "${index + 1}" }
        addView(label("$medal  ${metric.ip}${if (allowCopy) "  ⧉" else ""}", 15f, primary, true).apply {
            if (allowCopy) setOnClickListener { copy(metric.ip, "IP") }
        })
        addView(label("${metric.family} · ${metric.source} · Full ${metric.full.size} 轮", 11f, muted))
        val route = metric.route
        if (route?.ok == true) {
            addView(label("Argo 验证：TLS/SNI/Host ✓ · trace HTTP ${route.traceHttpCode}" +
                (if (route.wsPath.isNotEmpty()) " · WS 101 ✓" else "") , 11f, good, true))
        } else addView(label("Argo 验证失败：${route?.error ?: "未执行"}", 11f, bad))
        addView(label("平均 ${fmt(metric.avgCompleteMbps)} Mbps · 最低 ${fmt(metric.minCompleteMbps)} Mbps · 可靠下限 ${fmt(metric.floorMbps)} Mbps", 12f, secondary).apply { setPadding(0, dp(5), 0, 0) })
        addView(label("成功率 ${fmt(metric.fullSuccessRatePct)}% · 波动 ${fmt(metric.variationPct)}% · TTFB ${if (metric.medianTtfbMs < 0) "n/a" else "${fmt(metric.medianTtfbMs)} ms"} · ${metric.stability}", 11f, secondary))
        if (metric.primaryPop.isNotBlank()) addView(label("入口：${metric.primaryPop} · 亚洲评分 ${metric.edgeScore}${if (metric.popDrift) " · POP 漂移" else ""}", 11f, if (metric.edgeScore > 0) good else secondary, metric.edgeScore > 0))
        when {
            metric.pre?.ok == false -> addView(label("预检失败；未进入 Full，已按 0 计入。", 10.5f, warn))
            metric.micro?.ok == false -> addView(label("小流量筛选失败；未进入 Full，已按 0 计入。", 10.5f, warn))
            metric.full.isEmpty() -> addView(label("未进入 Full 固定轮次；已按 0 计入。", 10.5f, warn))
            metric.full.any { !it.ok } -> addView(label("含失败轮次，已按 0 Mbps 计入。", 10.5f, warn))
        }
        if (allowCopy) {
            val summary = nodeConfigSummary(metric.ip, argoHost, wsPath)
            addView(secondaryButton("复制 IP") { copy(metric.ip, "IP") }, lp(8))
            addView(primaryButton("复制 Argo 节点填写参数") { copy(summary, "Argo 节点参数") }, lp(8))
        } else addView(label("此项不可作为节点地址复制。", 10.5f, warn).apply { setPadding(0, dp(8), 0, 0) })
    }
    private fun nodeConfigSummary(ip: String, argoHost: String, wsPath: String): String =
        "地址/server：$ip\n端口：443\nSNI：$argoHost\nHost：$argoHost\nPath：${wsPath.ifBlank { "保持原节点配置" }}"
    private fun fmt(value: Double) = "%.1f".format(value)
    private fun saveHistory(all: Map<String, List<IpMetric>>, families: List<String>, invalid: Boolean, argoHost: String, wsPath: String) {
        try {
            val list = if (invalid) emptyList() else IpPipeline.rank(all.values.flatten().filter { it.isArgoUsable })
            val winner = list.firstOrNull(); val net = NetEnv.detect(this)
            val verdict = "$argoHost · Path ${wsPath.ifBlank { "保持原配置" }} · " + if (invalid) "网络变化，结果不可用于节点" else "Argo 入口固定多轮结果"
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

    private fun copy(value: String, kind: String) { try { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(kind, value)); Toast.makeText(this, "已复制：$value", Toast.LENGTH_SHORT).show() } catch (_: Exception) { Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show() } }
    private fun showConfirm(message: String, onConfirm: () -> Unit) {
        val dialog = Dialog(this); val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(20), dp(22), dp(16)); background = shape(card, 18, stroke) }; root.addView(label(message, 14f, primary).apply { setPadding(0, 0, 0, dp(16)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; row.addView(secondaryButton("取消") { dialog.dismiss() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) }); row.addView(primaryButton("继续") { dialog.dismiss(); onConfirm() }, LinearLayout.LayoutParams(0, dp(44), 1f)); root.addView(row); dialog.setContentView(root); dialog.window?.setBackgroundDrawableResource(android.R.color.transparent); dialog.show()
    }
    private fun switchTo(view: View, page: String) { currentPage = page; setContentView(view) }
    override fun onBackPressed() { when (currentPage) { "run" -> { runningJob?.cancel(); switchTo(home, "home"); refreshStatus() }; "result", "history" -> { switchTo(home, "home"); refreshStatus() }; "history_detail" -> showHistory(); else -> super.onBackPressed() } }
    override fun onDestroy() { unregisterNetworkWatch?.invoke(); logHandler.removeCallbacksAndMessages(null); scope.cancel(); super.onDestroy() }
}

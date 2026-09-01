package com.xiaowu7z.cfipoptimizer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.cf.ip.better.Better
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RR 的界面外壳只负责调用参考 APK 原版 Go 引擎、显示结果，以及响应用户点击手动写入 Cloudflare DNS。
 * 候选池、随机生成、RTT、CF-RAY、下载测速、速度计算和达标规则均在 libgojni.so 内执行。
 */
class MainActivity : Activity() {
    companion object {
        private const val PREFS = "cfip_prefs"
        private const val PREFS_USE_IPV4 = "use_ipv4"
        private const val PREFS_USE_TLS = "use_tls"
        private const val PREFS_BANDWIDTH = "bandwidth"
        private const val PREFS_HISTORY = "history_records"
        private const val MAX_HISTORY = 10
    }

    private data class NativeResult(
        val ip: String,
        val bandwidth: Long,
        val realBandwidth: Long,
        val maxSpeed: Long,
        val latencyMs: Long,
        val dataCenter: String,
        val elapsed: Long
    )

    private val bg = Color.parseColor("#060811")
    private val card = Color.parseColor("#101422")
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val running = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)

    private lateinit var home: View
    private lateinit var runPage: View
    private lateinit var bandwidthInput: EditText
    private lateinit var homeSummary: TextView
    private lateinit var runTitle: TextView
    private lateinit var runProgress: TextView
    private lateinit var stopButton: Button

    private var currentPage = "home"
    private var useIPv4 = true
    private var useTls = false
    private var runKind = "scan"
    private var progressPoller: Runnable? = null
    private var sessionCloudflareToken: CloudflareApiToken? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.navigationBarDividerColor = bg
        window.isNavigationBarContrastEnforced = false
        window.decorView.setBackgroundColor(bg)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        useIPv4 = prefs.getBoolean(PREFS_USE_IPV4, true)
        useTls = prefs.getBoolean(PREFS_USE_TLS, false)

        // Exact public JNI entry exported by the supplied reference APK.
        Better.setCacheDir(filesDir.absolutePath)

        buildHome()
        buildRunPage()
        switchTo(home, "home")
        refreshHomeSummary()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private fun shape(color: Int, radius: Int, border: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
        border?.let { setStroke(dp(1), it) }
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { if (top > 0) topMargin = dp(top) }

    private fun label(
        value: String,
        size: Float = 13f,
        color: Int = secondary,
        bold: Boolean = false
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun heading(value: String) = label(value, 13f, primary, true).apply {
        setPadding(0, 0, 0, dp(8))
    }

    private fun panel(block: LinearLayout.() -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = shape(card, 18, stroke)
        elevation = dp(2).toFloat()
        block()
    }

    private fun input(hint: String, password: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(muted)
        setTextColor(primary)
        textSize = 13f
        background = shape(Color.parseColor("#0A0E19"), 12, stroke)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        inputType = if (password) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT
        }
    }

    private fun primaryButton(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 14f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = shape(accent, 12)
        setOnClickListener { action() }
    }

    private fun secondaryButton(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 12f
        isAllCaps = false
        setTextColor(secondary)
        background = shape(off, 12, stroke)
        setOnClickListener { action() }
    }

    private fun segmented(
        values: List<String>,
        initial: String,
        select: (String) -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = linkedMapOf<String, Button>()
        fun mark(value: String, notify: Boolean) {
            buttons.forEach { (key, button) ->
                val selected = key == value
                button.setTextColor(if (selected) Color.WHITE else secondary)
                button.background = shape(
                    if (selected) accentSoft else off,
                    10,
                    if (selected) accent else stroke
                )
            }
            if (notify) select(value)
        }
        values.forEachIndexed { index, value ->
            val button = Button(this).apply {
                text = value
                textSize = 12f
                minHeight = 0
                minimumHeight = 0
                isAllCaps = false
                setPadding(dp(2), 0, dp(2), 0)
                setOnClickListener { mark(value, true) }
            }
            buttons[value] = button
            row.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                if (index < values.lastIndex) rightMargin = dp(6)
            })
        }
        mark(initial, false)
        return row
    }

    @SuppressLint("SetTextI18n")
    private fun buildHome() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
            setBackgroundColor(bg)
        }
        home = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            addView(root)
        }

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brand.addView(
            label("RR", 18f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                background = shape(accent, 13)
            },
            LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(12) }
        )
        brand.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("CF 优选IP", 25f, primary, true))
            addView(label("RR Edge Hunter · 参考 App 原版引擎", 12f, muted))
        })
        root.addView(brand)
        root.addView(label(
            "无需节点链接。候选池、RTT、CF-RAY、下载测速、速度计算与达标规则全部由你提供的参考 App 原版 Go 引擎执行。",
            12f,
            secondary
        ).apply { setPadding(0, dp(14), 0, dp(8)) })
        homeSummary = label("", 12f, good, true)
        root.addView(homeSummary)

        root.addView(panel {
            addView(heading("IP 协议"))
            addView(segmented(
                listOf("IPv4", "IPv6"),
                if (useIPv4) "IPv4" else "IPv6"
            ) {
                useIPv4 = it == "IPv4"
                refreshHomeSummary()
            })

            addView(heading("连接方式").apply { setPadding(0, dp(16), 0, dp(8)) })
            addView(segmented(
                listOf("TLS · 443", "非 TLS · 80"),
                if (useTls) "TLS · 443" else "非 TLS · 80"
            ) {
                useTls = it.startsWith("TLS")
                refreshHomeSummary()
            })

            addView(heading("期望带宽（Mbps）").apply { setPadding(0, dp(16), 0, dp(8)) })
            bandwidthInput = input("最小 1").apply {
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getInt(PREFS_BANDWIDTH, 1).coerceAtLeast(1).toString())
            }
            addView(bandwidthInput)
            addView(label(
                "原版流程会持续寻找首个达到期望带宽的 IP；期望值越高，耗时和流量越大。",
                11f,
                muted
            ).apply { setPadding(0, dp(8), 0, 0) })
        }, matchWrap(14))

        root.addView(primaryButton("开始优选") { startScan() }, matchWrap(16))
        root.addView(secondaryButton("更新参考 App 数据") { updateReferenceData() }, matchWrap(8))
        root.addView(secondaryButton("历史记录") { showHistory() }, matchWrap(8))
        root.addView(label(
            "找到结果后可复制 IP；需要解析时，由用户点击按钮并确认后写入 Cloudflare A/AAAA 灰云记录。不会自动修改 DNS。",
            11f,
            muted
        ).apply { setPadding(0, dp(12), 0, 0) })
    }

    private fun buildRunPage() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(24))
            setBackgroundColor(bg)
        }
        runPage = root
        runTitle = label("执行状态", 23f, primary, true)
        root.addView(runTitle)
        root.addView(ProgressBar(this).apply { isIndeterminate = true }, matchWrap(22))
        runProgress = label("正在初始化…", 13f, secondary).apply {
            setTextIsSelectable(true)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = shape(card, 16, stroke)
        }
        root.addView(ScrollView(this).apply { addView(runProgress) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply { topMargin = dp(16) })
        stopButton = primaryButton("停止扫描") { requestCancel() }.apply {
            background = shape(Color.parseColor("#7B3445"), 12)
        }
        root.addView(stopButton, matchWrap(14))
    }

    private fun refreshHomeSummary() {
        if (::homeSummary.isInitialized) {
            homeSummary.text = "${if (useIPv4) "IPv4" else "IPv6"} · ${if (useTls) "TLS 443" else "非 TLS 80"} · 原版测速引擎"
        }
    }

    private fun normalizedBandwidth(): Int {
        val value = bandwidthInput.text?.toString()?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        bandwidthInput.setText(value.toString())
        bandwidthInput.setSelection(bandwidthInput.text.length)
        return value
    }

    private fun saveSettings(bandwidth: Int) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREFS_USE_IPV4, useIPv4)
            .putBoolean(PREFS_USE_TLS, useTls)
            .putInt(PREFS_BANDWIDTH, bandwidth)
            .apply()
    }

    private fun startScan() {
        if (!running.compareAndSet(false, true)) {
            toast("已有任务正在运行")
            return
        }
        val bandwidth = normalizedBandwidth()
        saveSettings(bandwidth)
        cancelRequested.set(false)
        runKind = "scan"
        runTitle.text = "正在优选"
        runProgress.text = "正在初始化参考 App 引擎…"
        stopButton.isEnabled = true
        stopButton.text = "停止扫描"
        switchTo(runPage, "run")
        startProgressPolling()

        val ipv4 = useIPv4
        val tls = useTls
        executor.execute {
            try {
                val raw = Better.getIPs(ipv4, tls, bandwidth.toLong())
                mainHandler.post { finishScan(raw) }
            } catch (error: Exception) {
                mainHandler.post { finishWithError("扫描出错：${error.message ?: "未知错误"}") }
            }
        }
    }

    private fun updateReferenceData() {
        if (!running.compareAndSet(false, true)) {
            toast("已有任务正在运行")
            return
        }
        cancelRequested.set(false)
        runKind = "update"
        runTitle.text = "正在更新数据"
        runProgress.text = "正在调用参考 App 原版更新接口…"
        stopButton.isEnabled = false
        stopButton.text = "更新过程中请稍候"
        switchTo(runPage, "run")
        startProgressPolling()
        executor.execute {
            try {
                Better.updateData()
                mainHandler.post {
                    finishTask()
                    switchTo(home, "home")
                    toast("参考 App 数据已更新")
                }
            } catch (error: Exception) {
                mainHandler.post { finishWithError("更新失败：${error.message ?: "未知错误"}") }
            }
        }
    }

    private fun startProgressPolling() {
        stopProgressPolling()
        val poller = object : Runnable {
            override fun run() {
                if (!running.get()) return
                try {
                    Better.getProgress()?.takeIf { it.isNotBlank() }?.let { runProgress.text = it }
                } catch (_: Exception) {
                }
                mainHandler.postDelayed(this, 500L)
            }
        }
        progressPoller = poller
        mainHandler.postDelayed(poller, 500L)
    }

    private fun stopProgressPolling() {
        progressPoller?.let { mainHandler.removeCallbacks(it) }
        progressPoller = null
    }

    private fun requestCancel() {
        if (!running.get() || runKind != "scan") return
        cancelRequested.set(true)
        stopButton.isEnabled = false
        stopButton.text = "正在停止…"
        runProgress.text = "正在取消扫描…"
        try {
            Better.cancelScan()
        } catch (_: Exception) {
        }
    }

    private fun finishScan(raw: String?) {
        finishTask()
        if (cancelRequested.get() || raw.isNullOrBlank()) {
            switchTo(home, "home")
            toast("扫描已停止")
            return
        }
        try {
            val root = JSONObject(raw)
            val error = root.optString("error", "")
            if (error.isNotBlank()) {
                showMessagePage("任务未完成", error)
                return
            }
            val ip = root.optString("ip", "")
            if (ip.isBlank()) {
                switchTo(home, "home")
                toast("扫描未返回可用 IP")
                return
            }
            val result = NativeResult(
                ip = ip,
                bandwidth = root.optLong("bandwidth", 0),
                realBandwidth = root.optLong("realBandwidth", 0),
                maxSpeed = root.optLong("maxSpeed", 0),
                latencyMs = root.optLong("latencyMs", 0),
                dataCenter = root.optString("dataCenter", ""),
                elapsed = root.optLong("elapsed", 0)
            )
            saveHistory(result)
            showResult(result)
        } catch (error: Exception) {
            showMessagePage("任务未完成", "解析参考 App 结果失败：${error.message ?: "未知错误"}")
        }
    }

    private fun finishTask() {
        stopProgressPolling()
        running.set(false)
        stopButton.isEnabled = true
        stopButton.text = "停止扫描"
    }

    private fun finishWithError(message: String) {
        finishTask()
        showMessagePage("任务未完成", message)
    }

    private fun showMessagePage(title: String, message: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(24))
            setBackgroundColor(bg)
            addView(label(title, 23f, primary, true))
            addView(label(message, 13f, bad).apply {
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = shape(card, 16, stroke)
            }, matchWrap(18))
            addView(primaryButton("返回首页") { switchTo(home, "home") }, matchWrap(18))
        }
        switchTo(root, "result")
    }

    private fun showResult(result: NativeResult) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(30), dp(20), dp(24))
            setBackgroundColor(bg)
        }
        root.addView(label("优选完成", 24f, primary, true))
        root.addView(label("参考 App 原版引擎返回的首个达标结果", 12f, good, true).apply {
            setPadding(0, dp(5), 0, dp(14))
        })
        root.addView(resultCard(result))
        root.addView(primaryButton("复制 IP") { copy(result.ip, "CF-IP") }, matchWrap(16))
        root.addView(primaryButton("手动添加到 Cloudflare DNS") {
            showDnsSyncDialog(result.ip)
        }, matchWrap(8))
        root.addView(secondaryButton("返回首页") { switchTo(home, "home") }, matchWrap(8))
        switchTo(ScrollView(this).apply { setBackgroundColor(bg); addView(root) }, "result")
    }

    private fun resultCard(result: NativeResult) = panel {
        addView(label(result.ip, 21f, accent, true).apply {
            setPadding(0, 0, 0, dp(12))
            setOnClickListener { copy(result.ip, "CF-IP") }
        })
        addView(metric("期望带宽", "${result.bandwidth} Mbps"))
        addView(metric("实测带宽", "${result.realBandwidth} Mbps"), matchWrap(7))
        addView(metric("峰值速度", "${result.maxSpeed} kB/s"), matchWrap(7))
        addView(metric("往返延迟", "${result.latencyMs} ms"), matchWrap(7))
        addView(metric("数据中心", result.dataCenter.ifBlank { "-" }), matchWrap(7))
        addView(metric("总计用时", "${result.elapsed} 秒"), matchWrap(7))
    }

    private fun metric(name: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(label(name, 12f, muted), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(label(value, 13f, primary, true))
    }

    private fun saveHistory(result: NativeResult) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val old = try {
            JSONArray(prefs.getString(PREFS_HISTORY, "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        val all = JSONArray()
        all.put(JSONObject().apply {
            put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            put("ip", result.ip)
            put("bandwidth", result.bandwidth)
            put("realBandwidth", result.realBandwidth)
            put("maxSpeed", result.maxSpeed)
            put("latencyMs", result.latencyMs)
            put("dataCenter", result.dataCenter)
            put("elapsed", result.elapsed)
        })
        for (index in 0 until minOf(old.length(), MAX_HISTORY - 1)) {
            old.optJSONObject(index)?.let { all.put(it) }
        }
        prefs.edit().putString(PREFS_HISTORY, all.toString()).apply()
    }

    private fun loadHistory(): JSONArray = try {
        JSONArray(getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREFS_HISTORY, "[]"))
    } catch (_: Exception) {
        JSONArray()
    }

    private fun showHistory() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(30), dp(20), dp(24))
            setBackgroundColor(bg)
        }
        root.addView(label("历史记录", 24f, primary, true))
        val history = loadHistory()
        if (history.length() == 0) {
            root.addView(label("暂无历史记录", 13f, muted).apply { setPadding(0, dp(18), 0, 0) })
        } else {
            for (index in 0 until history.length()) {
                val item = history.optJSONObject(index) ?: continue
                val result = NativeResult(
                    item.optString("ip", ""),
                    item.optLong("bandwidth", 0),
                    item.optLong("realBandwidth", 0),
                    item.optLong("maxSpeed", 0),
                    item.optLong("latencyMs", 0),
                    item.optString("dataCenter", ""),
                    item.optLong("elapsed", 0)
                )
                root.addView(panel {
                    addView(label(item.optString("time", ""), 11f, muted))
                    addView(label(result.ip, 17f, accent, true).apply {
                        setPadding(0, dp(7), 0, dp(7))
                        setOnClickListener { copy(result.ip, "CF-IP") }
                    })
                    addView(label(
                        "实测 ${result.realBandwidth} Mbps / 目标 ${result.bandwidth} Mbps\n" +
                            "峰值 ${result.maxSpeed} kB/s / 延迟 ${result.latencyMs} ms\n" +
                            "数据中心 ${result.dataCenter.ifBlank { "-" }} / 用时 ${result.elapsed} 秒",
                        12f,
                        secondary
                    ))
                }, matchWrap(10))
            }
        }
        root.addView(secondaryButton("清空历史") {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(PREFS_HISTORY).apply()
            showHistory()
            toast("历史记录已清空")
        }, matchWrap(16))
        root.addView(primaryButton("返回首页") { switchTo(home, "home") }, matchWrap(8))
        switchTo(ScrollView(this).apply { setBackgroundColor(bg); addView(root) }, "history")
    }

    /** 用户主动点击后的唯一附加功能：将本轮唯一结果写入 Cloudflare A/AAAA 灰云记录。 */
    private fun showDnsSyncDialog(championIp: String) {
        val dialog = Dialog(this)
        val abandoned = AtomicBoolean(false)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(16))
            background = shape(card, 18, stroke)
        }
        val type = if (championIp.contains(':')) "AAAA" else "A"
        root.addView(label("手动添加解析", 17f, primary, true))
        root.addView(label(
            "将创建或更新 $type 灰云记录，使域名指向 $championIp。写入前会先生成只读预览。",
            11.5f,
            secondary
        ).apply { setPadding(0, dp(7), 0, dp(12)) })

        val prefs = getSharedPreferences("cf_dns_ui", MODE_PRIVATE)
        val zoneInput = input("Zone ID（32 位）").apply {
            setSingleLine(true)
            setText(prefs.getString("zone_id", "").orEmpty())
        }
        val recordInput = input("完整域名，例如 edge.example.com").apply {
            setSingleLine(true)
            setText(prefs.getString("record_name", "").orEmpty())
        }
        val storedToken = sessionCloudflareToken ?: CloudflareTokenStore.load(this)
        if (sessionCloudflareToken == null && storedToken != null) sessionCloudflareToken = storedToken
        val tokenInput = input(
            if (storedToken == null) "Cloudflare API Token" else "已有 Token，留空即可使用",
            password = true
        ).apply { setSingleLine(true) }
        root.addView(zoneInput)
        root.addView(recordInput, matchWrap(8))
        root.addView(tokenInput, matchWrap(8))
        root.addView(label(
            "Token 最小权限：目标 Zone 的 DNS Edit。仅写入这一条 A/AAAA 记录。",
            11f,
            warn
        ).apply { setPadding(0, dp(7), 0, 0) })
        val remember = CheckBox(this).apply {
            text = "在本机安全保存 Token（默认关闭）"
            setTextColor(secondary)
            textSize = 11.5f
            isChecked = false
            buttonTintList = ColorStateList.valueOf(accent)
        }
        root.addView(remember, matchWrap(8))
        root.addView(label(CloudflareTokenStore.SECURITY_NOTICE, 10.5f, muted))
        if (storedToken != null) {
            root.addView(secondaryButton("清除本机与会话 Token") {
                CloudflareTokenStore.clear(this)
                sessionCloudflareToken = null
                tokenInput.hint = "Cloudflare API Token"
                toast("已清除 Token")
            }, matchWrap(8))
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(secondaryButton("取消") {
            abandoned.set(true)
            dialog.dismiss()
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        val previewButton = primaryButton("生成只读预览") {}
        row.addView(previewButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(row, matchWrap(14))

        previewButton.setOnClickListener {
            val typed = tokenInput.text?.toString().orEmpty()
            val token = try {
                if (typed.isNotBlank()) CloudflareApiToken.parse(typed) else sessionCloudflareToken
                    ?: throw IllegalArgumentException("请填写 Cloudflare API Token")
            } catch (error: Exception) {
                toast(error.message ?: "API Token 无效", true)
                return@setOnClickListener
            }
            val config = try {
                CloudflareDns.normalizeConfig(CloudflareDns.Config(
                    zoneInput.text?.toString().orEmpty(),
                    recordInput.text?.toString().orEmpty(),
                    token
                ))
            } catch (error: Exception) {
                toast(error.message ?: "DNS 配置无效", true)
                return@setOnClickListener
            }
            previewButton.isEnabled = false
            previewButton.text = "正在读取记录…"
            val rememberToken = remember.isChecked
            tokenInput.setText("")
            scope.launch {
                try {
                    val plan = CloudflareDns.inspect(config, championIp)
                    postUiIfAlive {
                        if (!dialog.isShowing || !abandoned.compareAndSet(false, true)) return@postUiIfAlive
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
                                postUiIfAlive { toast("Token 保存失败，本次只在会话中使用", true) }
                            }
                        }
                        showDnsPlanConfirm(config, plan)
                    }
                } catch (error: Exception) {
                    postUiIfAlive {
                        if (abandoned.get() || !dialog.isShowing) return@postUiIfAlive
                        previewButton.isEnabled = true
                        previewButton.text = "生成只读预览"
                        toast(error.message ?: "DNS 预览失败", true)
                    }
                }
            }
        }
        dialog.setOnCancelListener { abandoned.set(true) }
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
        root.addView(label(
            if (plan.requiresWrite) "Cloudflare DNS 写入确认" else "DNS 无需更新",
            17f,
            primary,
            true
        ))
        root.addView(label(plan.confirmationText, 13f, primary, true).apply {
            setPadding(0, dp(10), 0, dp(12))
        })
        if (!plan.requiresWrite) {
            root.addView(primaryButton("完成") { dialog.dismiss() })
            dialog.setContentView(root)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.show()
            return
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cancel = secondaryButton("取消") { dialog.dismiss() }
        val apply = primaryButton("确认写入") {}
        row.addView(cancel, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        row.addView(apply, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(row)
        apply.setOnClickListener {
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            cancel.isEnabled = false
            apply.isEnabled = false
            apply.text = "写入并回读校验…"
            scope.launch {
                try {
                    val synced = CloudflareDns.apply(config, plan)
                    postUiIfAlive {
                        dialog.dismiss()
                        toast("${synced.type.name} ${synced.name} 已同步为 ${synced.content}", true)
                    }
                } catch (error: Exception) {
                    postUiIfAlive {
                        dialog.setCancelable(true)
                        dialog.setCanceledOnTouchOutside(true)
                        cancel.isEnabled = true
                        apply.isEnabled = true
                        apply.text = "确认写入"
                        toast(error.message ?: "DNS 写入失败", true)
                    }
                }
            }
        }
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun copy(value: String, label: String) {
        try {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(label, value))
            toast("已复制：$value")
        } catch (_: Exception) {
            toast("复制失败")
        }
    }

    private fun toast(message: String, long: Boolean = false) {
        Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private fun postUiIfAlive(block: () -> Unit) {
        if (isFinishing || isDestroyed) return
        runOnUiThread { if (!isFinishing && !isDestroyed) block() }
    }

    private fun switchTo(view: View, page: String) {
        currentPage = page
        setContentView(view)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (currentPage) {
            "run" -> {
                if (runKind == "scan") requestCancel()
                switchTo(home, "home")
            }
            "result", "history" -> switchTo(home, "home")
            else -> super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::bandwidthInput.isInitialized) saveSettings(normalizedBandwidth())
    }

    override fun onDestroy() {
        if (running.get() && runKind == "scan") {
            cancelRequested.set(true)
            try {
                Better.cancelScan()
            } catch (_: Exception) {
            }
        }
        stopProgressPolling()
        executor.shutdownNow()
        scope.cancel()
        super.onDestroy()
    }
}

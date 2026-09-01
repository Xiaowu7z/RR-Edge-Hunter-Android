import com.xiaowu7z.cfipoptimizer.CloudflareApiToken
import com.xiaowu7z.cfipoptimizer.CloudflareDns

private const val TOKEN = "test_token_abcdefghijklmnopqrstuvwxyz_123456"
private const val ZONE = "0123456789abcdef0123456789abcdef"
private const val A_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val AAAA_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
private const val CNAME_ID = "cccccccccccccccccccccccccccccccc"
private const val NS_ID = "dddddddddddddddddddddddddddddddd"

private class FakeDnsTransport(
    initial: List<CloudflareDns.DnsRecord> = emptyList()
) : CloudflareDns.Transport {
    val records = initial.toMutableList()
    var listCalls = 0
    var createCalls = 0
    var patchCalls = 0
    var getCalls = 0
    var lastCreate: CloudflareDns.CreateFields? = null
    var lastPatch: CloudflareDns.PatchFields? = null
    var throwWithSecret = false
    var corruptReadBack = false

    override fun listExact(
        token: CloudflareApiToken,
        zoneId: String,
        name: String
    ): List<CloudflareDns.DnsRecord> {
        listCalls++
        if (throwWithSecret) error("upstream accidentally echoed $TOKEN")
        return records.toList()
    }

    override fun create(
        token: CloudflareApiToken,
        zoneId: String,
        fields: CloudflareDns.CreateFields
    ): CloudflareDns.DnsRecord {
        createCalls++
        lastCreate = fields
        val id = if (fields.type == CloudflareDns.RecordType.A) A_ID else AAAA_ID
        return CloudflareDns.DnsRecord(
            id, fields.type.name, fields.name, fields.content, fields.ttl, fields.proxied
        ).also { records += it }
    }

    override fun patch(
        token: CloudflareApiToken,
        zoneId: String,
        recordId: String,
        fields: CloudflareDns.PatchFields
    ): CloudflareDns.DnsRecord {
        patchCalls++
        lastPatch = fields
        val index = records.indexOfFirst { it.id == recordId }
        check(index >= 0)
        val updated = records[index].copy(
            content = fields.content,
            ttl = fields.ttl,
            proxied = fields.proxied
        )
        records[index] = updated
        return updated
    }

    override fun get(
        token: CloudflareApiToken,
        zoneId: String,
        recordId: String
    ): CloudflareDns.DnsRecord {
        getCalls++
        val record = records.single { it.id == recordId }
        return if (corruptReadBack) record.copy(proxied = true) else record
    }
}

private fun record(
    id: String = A_ID,
    type: String = "A",
    name: String = "edge.example.com",
    content: String = "104.16.0.1",
    ttl: Int = 300,
    proxied: Boolean? = true
) = CloudflareDns.DnsRecord(id, type, name, content, ttl, proxied)

fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) {
            passed++
            println("PASS  $name")
        } else {
            failed++
            println("FAIL  $name  $detail")
        }
    }
    fun fails(name: String, messagePart: String? = null, block: () -> Unit) {
        try {
            block()
            check(name, false, "未抛出异常")
        } catch (error: Exception) {
            check(name, messagePart == null || error.message.orEmpty().contains(messagePart), error.message.orEmpty())
        }
    }

    val normalized = CloudflareDns.normalizeConfig(
        CloudflareDns.Config(ZONE.uppercase(), "Täst.Example.COM.", TOKEN)
    )
    check(
        "IDN、大小写和尾点规范化",
        normalized.zoneId == ZONE && normalized.recordName == "xn--tst-qla.example.com",
        normalized.toString()
    )
    check("Config 输出隐藏 Token", !normalized.toString().contains(TOKEN), normalized.toString())
    check(
        "Token 对象输出隐藏明文",
        !CloudflareApiToken.parse(TOKEN).toString().contains(TOKEN)
    )
    fails("拒绝非法 Zone ID", "32 位") {
        CloudflareDns.normalizeConfig(CloudflareDns.Config("bad", "edge.example.com", TOKEN))
    }
    fails("拒绝 URL 作为记录名") {
        CloudflareDns.normalizeConfig(CloudflareDns.Config(ZONE, "https://edge.example.com", TOKEN))
    }
    fails("拒绝 IP 作为记录名", "不能是 IP") {
        CloudflareDns.normalizeConfig(CloudflareDns.Config(ZONE, "104.16.0.1", TOKEN))
    }
    fails("拒绝含换行 Token") {
        CloudflareDns.Config(ZONE, "edge.example.com", "$TOKEN\nInjected")
    }

    val config = CloudflareDns.Config(ZONE, "edge.example.com", TOKEN)
    val createFake = FakeDnsTransport()
    val createPlan = CloudflareDns.inspect(config, "104.16.0.8", createFake)
    check(
        "0 条记录只生成创建预览且不写入",
        createPlan.action == CloudflareDns.Action.CREATE &&
            createPlan.type == CloudflareDns.RecordType.A &&
            createFake.createCalls == 0 && createFake.patchCalls == 0 &&
            createPlan.confirmationText.startsWith("将创建 A 记录"),
        createPlan.toString()
    )
    check("预览不包含 Token", !createPlan.toString().contains(TOKEN))
    val created = CloudflareDns.apply(config, createPlan, createFake)
    check(
        "确认后创建并回读验证",
        createFake.createCalls == 1 && createFake.getCalls == 1 &&
            created.action == CloudflareDns.Action.CREATE && created.readBackVerified &&
            createFake.lastCreate?.ttl == 1 && createFake.lastCreate?.proxied == false,
        created.toString()
    )

    val ipv6Fake = FakeDnsTransport()
    val ipv6Plan = CloudflareDns.inspect(config, "2606:4700:4700::1111", ipv6Fake)
    check("IPv6 自动选择 AAAA", ipv6Plan.type == CloudflareDns.RecordType.AAAA)

    val coexistFake = FakeDnsTransport(
        listOf(record(id = AAAA_ID, type = "AAAA", content = "2606:4700::1"))
    )
    val coexistPlan = CloudflareDns.inspect(config, "104.16.0.9", coexistFake)
    check("同名 AAAA 不阻止创建 A", coexistPlan.action == CloudflareDns.Action.CREATE)

    val updateFake = FakeDnsTransport(listOf(record()))
    val updatePlan = CloudflareDns.inspect(config, "104.16.0.9", updateFake)
    check(
        "1 条同名同类型生成更新预览",
        updatePlan.action == CloudflareDns.Action.UPDATE &&
            updatePlan.existingRecordId == A_ID && updatePlan.previousContent == "104.16.0.1"
    )
    val updated = CloudflareDns.apply(config, updatePlan, updateFake)
    check(
        "PATCH 只携带目标字段并强制灰云/自动 TTL",
        updateFake.patchCalls == 1 && updateFake.createCalls == 0 &&
            updateFake.lastPatch == CloudflareDns.PatchFields("104.16.0.9") &&
            updated.content == "104.16.0.9"
    )

    val unchangedFake = FakeDnsTransport(
        listOf(record(content = "104.16.0.9", ttl = 1, proxied = false))
    )
    val unchangedPlan = CloudflareDns.inspect(config, "104.16.0.9", unchangedFake)
    check(
        "内容、TTL、灰云均一致时标记无需更新",
        unchangedPlan.action == CloudflareDns.Action.UNCHANGED && !unchangedPlan.requiresWrite
    )
    val unchanged = CloudflareDns.apply(config, unchangedPlan, unchangedFake)
    check(
        "无需更新动作不产生重复写入",
        unchanged.action == CloudflareDns.Action.UNCHANGED &&
            unchangedFake.patchCalls == 0 && unchangedFake.createCalls == 0 &&
            unchangedFake.getCalls == 1
    )

    val duplicateFake = FakeDnsTransport(
        listOf(record(id = A_ID), record(id = AAAA_ID, content = "104.16.0.2"))
    )
    fails("多条同名同类型拒绝", "多条同名 A") {
        CloudflareDns.inspect(config, "104.16.0.9", duplicateFake)
    }
    check("重复记录错误路径不写入", duplicateFake.createCalls + duplicateFake.patchCalls == 0)

    val cnameFake = FakeDnsTransport(
        listOf(record(id = CNAME_ID, type = "CNAME", content = "origin.example.net"))
    )
    fails("同名 CNAME 冲突拒绝且不删除", "CNAME") {
        CloudflareDns.inspect(config, "104.16.0.9", cnameFake)
    }
    check(
        "CNAME 冲突路径无写入",
        cnameFake.createCalls == 0 && cnameFake.patchCalls == 0 && cnameFake.records.size == 1
    )

    val nsFake = FakeDnsTransport(
        listOf(record(id = NS_ID, type = "NS", content = "ns1.example.net"))
    )
    fails("同名 NS 冲突拒绝且不删除", "NS") {
        CloudflareDns.inspect(config, "104.16.0.9", nsFake)
    }
    check("NS 冲突路径无写入", nsFake.createCalls == 0 && nsFake.patchCalls == 0)

    val staleFake = FakeDnsTransport(listOf(record()))
    val stalePlan = CloudflareDns.inspect(config, "104.16.0.9", staleFake)
    staleFake.records[0] = staleFake.records[0].copy(content = "104.16.0.7")
    fails("确认前发生并发变化则 TOCTOU 拒绝", "重新预览") {
        CloudflareDns.apply(config, stalePlan, staleFake)
    }
    check("TOCTOU 拒绝后无写入", staleFake.patchCalls == 0 && staleFake.createCalls == 0)

    val corruptFake = FakeDnsTransport(listOf(record())).apply { corruptReadBack = true }
    val corruptPlan = CloudflareDns.inspect(config, "104.16.0.9", corruptFake)
    fails("写入后必须回读并验证", "回读校验失败") {
        CloudflareDns.apply(config, corruptPlan, corruptFake)
    }
    check("回读校验确实执行", corruptFake.patchCalls == 1 && corruptFake.getCalls == 1)

    val secretErrorFake = FakeDnsTransport().apply { throwWithSecret = true }
    try {
        CloudflareDns.inspect(config, "104.16.0.9", secretErrorFake)
        check("上游异常隐藏 Token", false, "未抛出异常")
    } catch (error: Exception) {
        check(
            "上游异常隐藏 Token",
            !error.message.orEmpty().contains(TOKEN) && error.cause == null,
            error.message.orEmpty()
        )
    }

    fails("冠军 IP 必须是公网字面量") {
        CloudflareDns.inspect(config, "192.168.1.1", FakeDnsTransport())
    }
    val externalChampion = CloudflareDns.inspect(config, "1.1.1.1", FakeDnsTransport())
    check(
        "参考引擎返回的公网 IP 可生成 DNS 预览",
        externalChampion.action == CloudflareDns.Action.CREATE &&
            externalChampion.content == "1.1.1.1" &&
            externalChampion.type == CloudflareDns.RecordType.A
    )

    println("CloudflareDnsTest：PASS $passed / FAIL $failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}

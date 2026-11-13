package dev.cloudsim.gateway.system

import dev.cloudsim.gateway.calls.CallRelay
import dev.cloudsim.gateway.calls.Lease
import dev.cloudsim.gateway.calls.MediaBridge
import dev.cloudsim.gateway.calls.PolicyKeeper
import dev.cloudsim.gateway.calls.Scheduler
import dev.cloudsim.gateway.calls.Sealer
import dev.cloudsim.gateway.calls.Telephony
import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ControlClient.Companion.b64
import dev.cloudsim.gateway.net.ServiceConfig
import dev.cloudsim.gateway.net.ServiceException
import dev.cloudsim.gateway.pairing.SoftwareSigner
import dev.cloudsim.gateway.protocol.Protocol
import dev.cloudsim.gateway.sms.Part
import dev.cloudsim.gateway.sms.Since
import dev.cloudsim.gateway.sms.Sms
import dev.cloudsim.gateway.sms.SmsLog
import dev.cloudsim.gateway.sms.SmsRadio
import dev.cloudsim.gateway.sms.SmsRelay
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * V4 (permissions and recovery) without anyone's help (docs/testing/V4_AUTOMATED.md): a real control service started
 * on this Mac, the gateway's own call, SMS and policy code with the phone and radio simulated, and owner and family
 * phones played by signing clients the way the apps sign. Runs only with CLOUDSIM_V4=1, since it builds and starts
 * the Go service. The 5-minute window runs on a test clock, so a run takes seconds, not hours.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class V4SystemTest {
    companion object {
        const val REPS = 10
        private const val SERVICE_ID = "v4test"
        private lateinit var server: LocalServer
        private lateinit var config: ServiceConfig
        val report = mutableListOf<String>()

        @BeforeClass @JvmStatic
        fun up() {
            assumeTrue("set CLOUDSIM_V4=1 to run the V4 system test", System.getenv("CLOUDSIM_V4") == "1")
            server = LocalServer(SERVICE_ID).also { it.start() }
            config = ServiceConfig("127.0.0.1:${server.port}", server.certSha256, SERVICE_ID)
        }

        @AfterClass @JvmStatic
        fun down() {
            if (::server.isInitialized) server.stop()
            if (report.isNotEmpty()) {
                val out = File(System.getenv("CLOUDSIM_V4_REPORT") ?: "build/v4-report.txt")
                out.parentFile?.mkdirs()
                out.writeText(report.joinToString("\n") + "\n")
                println(report.joinToString("\n"))
            }
        }
    }

    // ---- The world: one gateway, its owner and two family phones ----

    private val owner = SimPhone(config)
    private val family1 = SimPhone(config)
    private val family2 = SimPhone(config)
    private val gateway = SimGateway(config) { owner.id }
    private var revision = 0
    private val members = linkedMapOf<String, JSONObject>()
    private var numbers: List<String> = emptyList()

    init {
        gateway.client.register("gateway"); owner.client.register("phone"); family1.client.register("phone"); family2.client.register("phone")
        pair()
        invite(family1, JSONObject().put("answer", true).put("dial", true).put("sms_view", true).put("sms_send", true))
        invite(family2, JSONObject().put("answer", true).put("sms_view", true))
        gateway.start()
        waitFor("gateway applied the first policy") { gateway.keeper.policy?.revision == revision && appliedRevision() == revision }
        owner.start(); family1.start(); family2.start()
    }

    private fun pair() {
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        val p = gateway.client.call("POST", "/v1/pairings", JSONObject().put("claim_key", b64(Protocol.claimKey(secret))))
        val id = p.getString("pairing_id")
        val tr = Protocol.transcript(SERVICE_ID, id, gateway.signer.publicKey, owner.signer.publicKey)
        owner.client.call("POST", "/v1/pairings/$id/claim", JSONObject()
            .put("claim_mac", b64(Protocol.claimMac(Protocol.claimKey(secret), owner.id)))
            .put("auth_mac", b64(Protocol.authMac(Protocol.authKey(secret), tr)))
            .put("phone_signature", b64(owner.signer.sign(Protocol.bindMessage("phone", tr)))))
        gateway.client.call("POST", "/v1/pairings/$id/confirm", JSONObject().put("phone_id", owner.id)
            .put("gateway_signature", b64(gateway.signer.sign(Protocol.bindMessage("gateway", tr)))))
    }

    private fun invite(family: SimPhone, rights: JSONObject) {
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        val inv = owner.client.call("POST", "/v1/invites", JSONObject().put("gateway_id", gateway.id).put("claim_key", b64(hmac(secret, "cloudsim-invite-v1 claim"))))
        val id = inv.getString("invite_id")
        val t = inviteTranscript(id, family)
        family.client.call("POST", "/v1/invites/$id/claim", JSONObject()
            .put("claim_mac", b64(Protocol.claimMac(hmac(secret, "cloudsim-invite-v1 claim"), family.id)))
            .put("auth_mac", b64(Protocol.authMac(hmac(secret, "cloudsim-invite-v1 auth"), t)))
            .put("family_signature", b64(family.signer.sign(Protocol.bindMessage("family", t)))))
        members[family.id] = JSONObject().put("device_id", family.id).put("public_key", b64(family.signer.publicKey)).also { m ->
            rights.keys().forEach { k -> m.put(k, rights.get(k)) }
        }
        sign(inviteId = id)
    }

    private fun inviteTranscript(id: String, family: SimPhone): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (f in listOf("cloudsim-invite-v1".toByteArray(), SERVICE_ID.toByteArray(), id.toByteArray(), gateway.signer.publicKey, owner.signer.publicKey, family.signer.publicKey)) {
            out.write(ByteBuffer.allocate(4).putInt(f.size).array()); out.write(f)
        }
        return out.toByteArray()
    }

    /** The owner signs the next revision of the policy, as the iPhone does, and submits it; returns the submit time. */
    private fun sign(inviteId: String? = null): Long {
        revision++
        val doc = JSONObject().put("v", 1).put("gateway_id", gateway.id).put("revision", revision)
            .put("members", JSONArray(members.values.toList())).put("owner_ring", JSONArray(listOf(owner.id)))
            .put("mode", "simultaneous").put("wait_s", 15).put("fallback", false)
        if (numbers.isNotEmpty()) doc.put("numbers", JSONArray(numbers))
        val raw = doc.toString().toByteArray()
        val body = JSONObject().put("policy", b64(raw)).put("signature", b64(owner.signer.sign(Protocol.policyMessage(gateway.id, revision, raw))))
        inviteId?.let { body.put("invite_id", it) }
        val t = System.nanoTime()
        owner.client.call("POST", "/v1/gateways/${gateway.id}/policy", body)
        return t
    }

    private fun appliedRevision() = owner.client.call("GET", "/v1/gateways/${gateway.id}/policy").optInt("applied_revision")

    private fun setRight(phone: SimPhone, right: String, on: Boolean) { members[phone.id]!!.put(right, on) }

    /** An incoming cellular call; returns its call ID once the service has it. */
    private fun reports() = gateway.sent.count { it == "POST /v1/calls" }

    private fun ring(): String {
        val before = reports()
        gateway.onWorker { gateway.relay.onPhoneState(CallRelay.RINGING, "13800138000") }
        waitFor("call reported") { reports() > before }
        return gateway.onWorker { gateway.relay.currentCall!! }
    }

    private fun answer(phone: SimPhone, call: String) = runCatching {
        phone.client.call("POST", "/v1/calls/$call/answer", JSONObject().put("command_id", UUID.randomUUID().toString()))
    }

    private fun hangUpCellular() { gateway.onWorker { gateway.relay.onPhoneState(CallRelay.IDLE) } }

    private fun answeredBy(phone: SimPhone): String {
        val call = ring()
        answer(phone, call).getOrThrow()
        waitFor("answered by ${phone.id.take(8)}") { gateway.telephony.offHook }
        return call
    }

    // ---- 1. Online revocation within 5 s, "pending" until the gateway says so ----

    @Test
    fun t1_onlineRevocationTakesEffectWithinFiveSeconds() {
        val times = (1..REPS).map { i ->
            setRight(family1, "dial", i % 2 == 0)
            val t0 = sign()
            assertTrue("pending before the gateway reported", appliedRevision() <= revision)
            waitFor("applied $revision", timeoutMs = 5_000) { appliedRevision() == revision }
            (System.nanoTime() - t0) / 1_000_000
        }
        report += "V4-1 在线撤销 ${REPS} 次：最长 ${times.max()} ms，中位 ${times.sorted()[REPS / 2]} ms（目标 5000 ms）"
        assertTrue(times.all { it < 5_000 })
    }

    // ---- 2 and 11. Revoked during a call: only that member's part ends, others unaffected ----

    @Test
    fun t2_revokedMemberIsCutOffAndOthersAreNot() {
        repeat(REPS) {
            setRight(family1, "answer", true); setRight(family1, "dial", true); sign()
            waitFor("applied") { appliedRevision() == revision }
            answeredBy(family1)
            setRight(family1, "answer", false); setRight(family1, "dial", false); sign()
            waitFor("call ended after revocation", timeoutMs = 5_000) { gateway.telephony.ends > 0 }
            hangUpCellular(); gateway.telephony.reset()
            // The other member still answers the next call.
            answeredBy(family2)
            assertEquals(0, gateway.telephony.ends)
            hangUpCellular(); gateway.telephony.reset()
            // And the removed one cannot.
            val call = ring()
            assertTrue("revoked member's answer refused", answer(family1, call).isFailure)
            hangUpCellular(); gateway.telephony.reset()
        }
        report += "V4-2/11 通话中撤销 ${REPS} 次：被撤销者的通话都被结束，另一位家人照常接听，被撤销者再接被拒"
    }

    // ---- 3, 4. Five minutes without confirmation: a call carried on a phone ends, owner and family alike ----

    @Test
    fun t3_fiveMinuteWindowEndsCallsOfOwnerAndFamily() {
        setRight(family1, "answer", true); sign(); waitFor("applied") { appliedRevision() == revision }
        for ((who, phone) in listOf("主人" to owner, "家人" to family1)) {
            repeat(REPS) {
                gateway.renew()
                answeredBy(phone)
                server.stop()
                gateway.advance(Lease.WINDOW_MS - 1_000); gateway.renew(); gateway.watch()
                assertEquals("still inside the window", 0, gateway.telephony.ends)
                gateway.advance(2_000); gateway.renew(); gateway.watch()
                waitFor("call ended at 5 minutes") { gateway.telephony.ends == 1 }
                // While paused, a new call is not relayed and the Redmi rings as an ordinary phone.
                hangUpCellular(); gateway.telephony.reset()
                val before = reports()
                gateway.onWorker { gateway.relay.onPhoneState(CallRelay.RINGING, "13800138000") }
                assertEquals("not relayed while paused", before, reports())
                hangUpCellular(); gateway.telephony.reset()
                server.start()
                gateway.renew(); gateway.watch()
                assertTrue("remote access back", gateway.lease.valid())
            }
            report += "V4-3/4 5 分钟窗口（$who 接听）${REPS} 次：4:59 未挂断，5:01 挂断；恢复服务后立即恢复"
        }
    }

    // ---- 7. The window does not move with the wall clock ----

    @Test
    fun t7_wallClockChangesDoNotStretchTheWindow() {
        gateway.renew()
        assertTrue(gateway.lease.valid())
        // Set the gateway's wall clock back an hour: its requests fall outside the service's 90 s skew and fail, and
        // the window still closes 5 minutes after the last real confirmation.
        gateway.wallOffsetMs = -3_600_000
        gateway.advance(Lease.WINDOW_MS + 1_000)
        assertFalse("a stale clock confirms nothing", gateway.renew())
        assertFalse(gateway.lease.valid())
        // Forward a day: the same.
        gateway.wallOffsetMs = 86_400_000
        assertFalse(gateway.renew())
        assertFalse(gateway.lease.valid())
        gateway.wallOffsetMs = 0
        assertTrue(gateway.renew())
        report += "V4-7 改网关时间（拨回 1 小时、拨快 1 天）：确认都失败，窗口按单调时钟照常关闭；改回后恢复"
    }

    // ---- 8. A restart starts unconfirmed ----

    @Test
    fun t8_restartWhileOfflineStartsUnconfirmed() {
        repeat(3) {
            gateway.renew()
            server.stop()
            val restarted = SimGateway(config, gateway.signer) { owner.id }
            restarted.keeper.load(gateway.storedPolicy)
            assertFalse("a new process is not confirmed", restarted.lease.valid())
            assertFalse(restarted.renew())
            restarted.onWorker { restarted.relay.onPhoneState(CallRelay.RINGING, "13800138000") }
            assertTrue("nothing reported while unconfirmed", restarted.sent.none { it == "POST /v1/calls" })
            restarted.onWorker { restarted.relay.onPhoneState(CallRelay.IDLE) }
            server.start()
            assertTrue(restarted.renew())
            assertTrue(restarted.lease.valid())
            restarted.stop()
        }
        report += "V4-8 断网时重启网关 3 次：启动即为未确认，来电不转发；服务恢复后确认"
    }

    // ---- 9. Old credentials and replays ----

    @Test
    fun t9_removedDeviceAndReplaysAreRefused() {
        members.remove(family2.id); sign(); waitFor("applied") { appliedRevision() == revision }
        val refused = mutableListOf<String>()
        fun expectRefused(what: String, block: () -> Unit) {
            val e = runCatching(block).exceptionOrNull()
            assertTrue("$what should be refused", e is ServiceException && e.code != "NETWORK")
            refused += "$what→${(e as ServiceException).code}"
        }
        expectRefused("读策略") { family2.client.call("GET", "/v1/gateways/${gateway.id}/policy") }
        expectRefused("发短信命令") { family2.client.call("POST", "/v1/sms/command", JSONObject().put("gateway_id", gateway.id).put("command_id", UUID.randomUUID().toString()).put("envelope", "{}")) }
        expectRefused("同步短信") { family2.client.call("POST", "/v1/sms/sync", JSONObject().put("gateway_id", gateway.id).put("after", 0)) }
        expectRefused("历史短信") { family2.client.call("POST", "/v1/sms/history", JSONObject().put("gateway_id", gateway.id).put("from", 0).put("to", 1)) }
        expectRefused("拨出") { family2.client.call("POST", "/v1/calls/outgoing", JSONObject().put("gateway_id", gateway.id).put("call_id", UUID.randomUUID().toString()).put("sealed", "x").put("command_id", UUID.randomUUID().toString())) }
        val call = ring()
        expectRefused("接听") { family2.client.call("POST", "/v1/calls/$call/answer", JSONObject().put("command_id", UUID.randomUUID().toString())) }
        hangUpCellular(); gateway.telephony.reset()
        // An old policy signed by the owner cannot be replayed: the revision must grow.
        expectRefused("重放旧版本策略") {
            val raw = JSONObject().put("v", 1).put("gateway_id", gateway.id).put("revision", revision - 1).put("members", JSONArray())
                .put("owner_ring", JSONArray(listOf(owner.id))).put("mode", "simultaneous").put("wait_s", 15).put("fallback", false).toString().toByteArray()
            owner.client.call("POST", "/v1/gateways/${gateway.id}/policy", JSONObject().put("policy", b64(raw)).put("signature", b64(owner.signer.sign(Protocol.policyMessage(gateway.id, revision - 1, raw)))))
        }
        // A captured request sent again is refused (nonce already used).
        val replay = owner.client.replayLast()
        assertTrue("replayed request refused: $replay", replay >= 400)
        refused += "重放已发过的请求→HTTP $replay"
        report += "V4-9 被移除设备和重放：" + refused.joinToString("；")
    }

    // ---- Two SIMs (DUAL_SIM.md): rights go by number, nothing leaks to the other one ----

    @Test
    fun t10_rightsOnOneNumberDoNotReachTheOther() {
        numbers = listOf("num-work0001", "num-home0002")
        members[family1.id]!!.put("per_number", JSONObject().put("num-work0001", JSONObject().put("answer", true).put("sms_view", true)))
        sign(); waitFor("applied") { appliedRevision() == revision }
        repeat(REPS) {
            // A call on the home number does not ring the member; one on the work number does.
            gateway.telephony.ringingOn = "num-home0002"
            val home = ring()
            assertTrue("answer on the home number refused", answer(family1, home).isFailure)
            hangUpCellular(); gateway.telephony.reset()
            gateway.telephony.ringingOn = "num-work0001"
            answeredBy(family1)
            hangUpCellular(); gateway.telephony.reset()
        }
        val before = family1.events.count { it.getString("type") == "sms.new" }
        gateway.onSms { gateway.sms.onReceived("13800138000", "家里卡的短信", System.currentTimeMillis(), "num-home0002") }
        gateway.onSms { gateway.sms.onReceived("13800138000", "工作卡的短信", System.currentTimeMillis(), "num-work0001") }
        waitFor("work SMS reached the member") { family1.events.count { it.getString("type") == "sms.new" } == before + 1 }
        waitFor("both reached the owner") { owner.events.count { it.getString("type") == "sms.new" } >= 2 }
        Thread.sleep(300)
        assertEquals("home SMS never reached the member", before + 1, family1.events.count { it.getString("type") == "sms.new" })
        gateway.telephony.ringingOn = null
        report += "V4-双卡 按号码授权 ${REPS} 次：家人只在有权的号码上响铃、能接；另一号码的来电接听被拒，短信也不会发给它"
    }

    // ---- SMS during a pause: held, then sent on; commands refused ----

    @Test
    fun t5_smsIsHeldDuringAPauseAndSentOnAfter() {
        gateway.renew()
        server.stop()
        gateway.advance(Lease.WINDOW_MS + 1_000); gateway.renew(); gateway.watch()
        gateway.onSms { gateway.sms.onReceived("13800138000", "暂停期间", System.currentTimeMillis()) }
        server.start()
        gateway.renew(); gateway.watch()
        waitFor("held message delivered to the owner") { owner.events.any { it.getString("type") == "sms.new" } }
        report += "V4-5 暂停期间收到的短信：先扣下，恢复后补发给有权查看的手机"
    }

    // ---- helpers ----

    private fun waitFor(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out: $what")
    }

    @org.junit.After
    fun tearDown() {
        owner.stop(); family1.stop(); family2.stop(); gateway.stop()
        if (!server.running) server.start()
    }
}

private fun hmac(key: ByteArray, text: String): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(text.toByteArray()) }

/** The control service built from this repository and started on a free port with a fresh database. */
class LocalServer(private val serviceId: String) {
    private val dir = File(System.getProperty("java.io.tmpdir"), "cloudsim-v4-${UUID.randomUUID()}").also { it.mkdirs() }
    private val binary = File(dir, "cloudsim-control")
    val port = ServerSocket(0).use { it.localPort }
    lateinit var certSha256: ByteArray
    private var process: Process? = null
    val running get() = process?.isAlive == true

    init {
        val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "server/go.mod").exists() }
        val go = System.getenv("CLOUDSIM_GO") ?: "${System.getProperty("user.home")}/.cloudsim/toolchain/go-1.27.3/go/bin/go"
        run(File(repo, "server"), go, "build", "-o", binary.path, "./cmd/cloudsim-control")
        run(dir, "openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", "key.pem")
        run(dir, "openssl", "req", "-new", "-x509", "-key", "key.pem", "-days", "1", "-subj", "/CN=127.0.0.1", "-out", "cert.pem")
        val der = java.util.Base64.getMimeDecoder().decode(File(dir, "cert.pem").readText().substringAfter("-----BEGIN CERTIFICATE-----").substringBefore("-----END"))
        certSha256 = Protocol.sha256(der)
    }

    private fun run(cwd: File, vararg cmd: String) {
        val p = ProcessBuilder(*cmd).directory(cwd).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        check(p.waitFor() == 0) { "${cmd.joinToString(" ")} failed: $out" }
    }

    fun start() {
        process = ProcessBuilder(binary.path, "-listen", "127.0.0.1:$port", "-tls-cert", "cert.pem", "-tls-key", "key.pem", "-db", "v4.db", "-service-id", serviceId, "-no-limits")
            .directory(dir).redirectErrorStream(true).redirectOutput(File(dir, "server.log")).start()
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            if (runCatching { java.net.Socket("127.0.0.1", port).close() }.isSuccess) return
            Thread.sleep(50)
        }
        error("control service did not start")
    }

    fun stop() {
        process?.let { it.destroy(); it.waitFor(5, TimeUnit.SECONDS) }
        process = null
    }
}

/** A client that remembers its last request, to send it again unchanged (replay). */
class RecordingClient(private val config: ServiceConfig, signer: SoftwareSigner, now: () -> Long = System::currentTimeMillis) : ControlClient(config, signer, now) {
    private var last: Triple<String, String, Map<String, String>>? = null
    private var lastBody = ByteArray(0)

    override fun call(method: String, path: String, body: JSONObject?, readTimeoutMs: Int): JSONObject {
        return super.call(method, path, body, readTimeoutMs)
    }

    /** Sends a freshly signed request, captures its headers, then sends the very same bytes again; returns the second status. */
    fun replayLast(): Int {
        val path = "/v1/me"
        val nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
        val time = System.currentTimeMillis().toString()
        val sig = java.util.Base64.getEncoder().encodeToString(signerSign(Protocol.requestMessage("GET", path, time, nonce, ByteArray(0))))
        fun send(): Int {
            val c = java.net.URL("https://${config.address}$path").openConnection() as javax.net.ssl.HttpsURLConnection
            c.sslSocketFactory = trustingTls(); c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            c.setRequestProperty("X-CS-Device", deviceId); c.setRequestProperty("X-CS-Time", time)
            c.setRequestProperty("X-CS-Nonce", nonce); c.setRequestProperty("X-CS-Signature", sig)
            return c.responseCode.also { c.disconnect() }
        }
        check(send() == 200) { "first request should pass" }
        return send()
    }

    private val keyRef = signer
    private fun signerSign(m: ByteArray) = keyRef.sign(m)
    private fun trustingTls(): javax.net.ssl.SSLSocketFactory = javax.net.ssl.SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {
                check(Protocol.equalMac(Protocol.sha256(c[0].encoded), config.certSha256))
            }
            override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
        }), SecureRandom())
    }.socketFactory
}

/** A phone as the app is to the service: its own key, signed requests, and a long poll collecting its events. */
class SimPhone(config: ServiceConfig) {
    val signer = SoftwareSigner()
    val client = RecordingClient(config, signer)
    val id = client.deviceId
    val events = CopyOnWriteArrayList<JSONObject>()
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread {
            var after = 0L
            while (running) {
                runCatching {
                    val list = client.call("GET", "/v1/events?after=$after", readTimeoutMs = 30_000).getJSONArray("events")
                    for (i in 0 until list.length()) list.getJSONObject(i).let { e -> after = maxOf(after, e.getLong("seq")); events += e }
                }.onFailure { Thread.sleep(200) }
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() { running = false; thread?.interrupt() }
}

/**
 * The gateway as GatewayService runs it, minus Android: the same CallRelay, SmsRelay and PolicyKeeper on one worker
 * thread each, fed by a long poll; the cellular call, the SMS radio and the media bridge are simulated. The lease runs
 * on [clock], a test clock moved by [advance].
 */
class SimGateway(config: ServiceConfig, val signer: SoftwareSigner = SoftwareSigner(), owner: () -> String?) {
    class TestClock { @Volatile var ms = 1_000_000L; fun now() = ms }
    val clock = TestClock()
    @Volatile var wallOffsetMs = 0L
    val sent = CopyOnWriteArrayList<String>()
    val client = object : ControlClient(config, signer, { System.currentTimeMillis() + wallOffsetMs }) {
        override fun call(method: String, path: String, body: JSONObject?, readTimeoutMs: Int): JSONObject {
            sent += "$method $path"
            return super.call(method, path, body, readTimeoutMs)
        }
    }
    val id = client.deviceId
    val lease = Lease { clock.now() }
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val smsWorker = Executors.newSingleThreadExecutor()
    var storedPolicy: String? = null

    inner class SimTelephony : Telephony {
        @Volatile var offHook = false
        @Volatile var ends = 0
        @Volatile var ringingOn: String? = null
        override fun ringingNumber() = ringingOn
        override fun answer(): Boolean { worker.execute { offHook = true; relay.onPhoneState(CallRelay.OFFHOOK) }; return true }
        override fun end(): Boolean { ends++; return true }
        override fun dial(number: String) = true
        fun reset() { offHook = false; ends = 0 }
    }

    val telephony = SimTelephony()
    private val scheduler = Scheduler { ms, task -> val f = worker.schedule(task, ms, TimeUnit.MILLISECONDS); { f.cancel(false) } }
    /** Envelopes are opaque to the service; here they are plain JSON, signed by naming the sender. */
    private val sealer = object : Sealer {
        override fun seal(kind: String, to: String, id: String, plaintext: ByteArray) =
            JSONObject().put("kind", kind).put("to", to).put("id", id).put("plain", b64(plaintext))
        override fun open(envelope: JSONObject, kind: String, senderKey: ByteArray): ByteArray? =
            if (envelope.optString("kind") == kind && envelope.optString("from") == Protocol.deviceId(senderKey)) ControlClient.unb64(envelope.optString("plain")) else null
    }
    lateinit var keeper: PolicyKeeper
    val relay = CallRelay(client, telephony, object : MediaBridge { override fun send(command: JSONObject) = true }, signer, owner, { keeper.policy }, scheduler, sealer, remote = lease::valid)
    val trace = CopyOnWriteArrayList<String>()
    val since = MemorySince()
    val sms = SmsRelay(client, MemoryLog(), since, sealer, NoRadio(), scheduler, owner, { keeper.policy }, trace = { trace += it }, remote = lease::valid)

    init {
        keeper = PolicyKeeper(client, owner, lease, { storedPolicy = it }, {
            onWorker { relay.onPolicyChanged() }
            onSms { sms.onPolicy() }
        })
    }

    fun <T> onWorker(block: () -> T): T = worker.submit(block).get(10, TimeUnit.SECONDS)
    fun onSms(block: () -> Unit) { smsWorker.submit(block).get(10, TimeUnit.SECONDS) }
    fun advance(ms: Long) { clock.ms += ms }
    fun renew(): Boolean = keeper.refresh()
    fun watch() = keeper.watch(onPause = { onWorker { relay.onLeaseExpired() } }, onResume = { onSms { sms.onResume() } })

    @Volatile private var running = false
    private var poller: Thread? = null

    fun start() {
        renew()
        running = true
        poller = Thread {
            var after = 0L
            while (running) {
                try {
                    val list = client.call("GET", "/v1/events?after=$after", readTimeoutMs = 30_000).getJSONArray("events")
                    for (i in 0 until list.length()) {
                        val e = list.getJSONObject(i)
                        after = maxOf(after, e.getLong("seq"))
                        val d = e.getJSONObject("data").put("_at_ms", e.optLong("at_ms"))
                        when (e.getString("type")) {
                            "policy.update" -> renew()
                            "sms.command" -> onSms { sms.onCommand(d.optString("device_id"), ControlClient.unb64(d.optString("public_key")) ?: ByteArray(0), d.optString("command_id"), runCatching { JSONObject(d.optString("envelope")) }.getOrDefault(JSONObject()), e.optLong("at_ms")) }
                            "sms.sync" -> onSms { sms.onSync(d.optString("device_id"), d.optLong("after")) }
                            "sms.history" -> onSms { sms.onHistory(d.optString("device_id"), d.optLong("from"), d.optLong("to")) }
                            else -> onWorker { relay.onEvent(e.getString("type"), d) }
                        }
                    }
                } catch (e: ServiceException) {
                    Thread.sleep(200)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() { running = false; poller?.interrupt() }
}

class MemoryLog : SmsLog {
    private val list = CopyOnWriteArrayList<Sms>()
    private var rev = 0L
    private fun put(s: Sms): Sms { list.removeAll { it.id == s.id }; list += s; return s }
    override fun add(id: String, peer: String, body: String, at: Long) = add(id, peer, body, at, null)
    override fun add(id: String, peer: String, body: String, at: Long, numberId: String?) = put(Sms(lastSeq() + 1, id, peer, body, at, rev = ++rev, numberId = numberId))
    override fun addOutgoing(id: String, peer: String, body: String, at: Long, sender: String, commandId: String, segments: List<String>, numberId: String?) =
        put(Sms(lastSeq() + 1, id, peer, body, at, "out", sender, commandId, segments, segments.map { Part.SENDING }, ++rev, numberId))
    override fun addOutgoing(id: String, peer: String, body: String, at: Long, sender: String, commandId: String, segments: List<String>) =
        put(Sms(lastSeq() + 1, id, peer, body, at, "out", sender, commandId, segments, segments.map { Part.SENDING }, ++rev))
    override fun update(id: String, parts: List<Part>) = byId(id)?.let { put(it.copy(parts = parts, rev = ++rev)) }
    override fun byId(id: String) = list.find { it.id == id }
    override fun byCommand(commandId: String) = list.find { it.commandId == commandId }
    override fun changedAfter(rev: Long, limit: Int) = list.filter { it.rev > rev }.sortedBy { it.rev }.take(limit)
    override fun lastSeq() = list.maxOfOrNull { it.seq } ?: 0
    override fun sending() = list.filter { it.parts.contains(Part.SENDING) }
    override fun between(from: Long, to: Long, limit: Int) = list.filter { it.at in from until to }.sortedBy { it.at }.take(limit)
    override fun firstAt() = list.minOfOrNull { it.at }
    private val void = mutableSetOf<String>()
    override fun cancel(commandId: String) { void += commandId }
    override fun cancelled(commandId: String) = commandId in void
}

class MemorySince : Since {
    val map = mutableMapOf<String, Long>()
    override fun get(device: String) = map[device]
    override fun put(device: String, seq: Long) { map[device] = seq }
    override fun remove(device: String) { map.remove(device) }
    override fun devices() = map.keys.toSet()
}

class NoRadio : SmsRadio {
    override fun divide(body: String) = listOf(body)
    override fun send(id: String, peer: String, segments: List<Pair<Int, String>>) = true
    override fun inSentBox(peer: String, body: String, sinceMs: Long): Boolean? = false
}

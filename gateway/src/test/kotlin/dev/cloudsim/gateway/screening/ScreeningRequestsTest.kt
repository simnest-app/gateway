package dev.cloudsim.gateway.screening

import dev.cloudsim.gateway.calls.Sealer
import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ControlClient.Companion.b64
import dev.cloudsim.gateway.net.ServiceConfig
import dev.cloudsim.gateway.pairing.SoftwareSigner
import dev.cloudsim.gateway.protocol.Protocol
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class RequestsClient : ControlClient(ServiceConfig("127.0.0.1:1", ByteArray(32), "svc"), SoftwareSigner()) {
    val delivered = mutableListOf<JSONObject>()
    override fun call(method: String, path: String, body: JSONObject?, readTimeoutMs: Int): JSONObject {
        if (path == "/v1/records/deliver") delivered += JSONObject(JSONObject(body!!.getString("envelope")).getString("plain"))
        return JSONObject()
    }
}

private class RequestsSealer : Sealer {
    override fun seal(kind: String, to: String, id: String, plaintext: ByteArray) = JSONObject().put("kind", kind).put("plain", plaintext.decodeToString())
    override fun open(envelope: JSONObject, kind: String, senderKey: ByteArray): ByteArray? =
        if (envelope.optString("kind") == kind && envelope.optString("signer") == b64(senderKey)) envelope.getString("plain").toByteArray() else null
}

class ScreeningRequestsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val client = RequestsClient()
    private val ownerKey = SoftwareSigner()
    private val owner = Protocol.deviceId(ownerKey.publicKey)
    private val key = ByteArray(32) { 3 }
    private val store by lazy { ScreenAssetStore(tmp.root) }
    /** "Decodes" a clip to a tenth of a second per byte. */
    private val requests by lazy { ScreeningRequests(client, RequestsSealer(), { owner }, store, { key }, { b -> ShortArray(b.size * 1600) }) }
    private var n = 0

    private fun send(payload: JSONObject, signer: SoftwareSigner = ownerKey) {
        val id = "screening-${n++}-0000"
        requests.onRequest(JSONObject().put("device_id", Protocol.deviceId(signer.publicKey)).put("request_id", id).put("public_key", b64(signer.publicKey))
            .put("envelope", JSONObject().put("kind", "screening").put("signer", b64(signer.publicKey)).put("plain", payload.put("request_id", id).toString()).toString()))
    }

    private fun answer() = client.delivered.last().getJSONArray("items").getJSONObject(0)

    @Test
    fun ownerGetsTheKey() {
        send(JSONObject().put("op", "key"))
        assertEquals(b64(key), answer().getString("key"))
        val stranger = SoftwareSigner()
        send(JSONObject().put("op", "key"), stranger)
        assertEquals(1, client.delivered.size)
    }

    @Test
    fun aSetInPiecesIsInstalledWithItsDigest() {
        val greeting = ByteArray(50) { 1 }
        val bundle = JSONObject().put("clips", JSONObject().put("greeting", b64(greeting)).put("reply0", b64(ByteArray(20) { 2 })))
            .put("allow", JSONArray().put(b64(ByteArray(16) { 9 }))).toString().toByteArray()
        val half = bundle.size / 2
        listOf(bundle.copyOfRange(0, half), bundle.copyOfRange(half, bundle.size)).forEachIndexed { i, piece ->
            send(JSONObject().put("op", "asset").put("number", "sim1").put("set", "set-00000001").put("index", i).put("count", 2).put("chunk", b64(piece)))
        }
        val digest = answer().getString("digest")
        assertEquals(ScreenAssetStore.digest(mapOf("greeting" to greeting, "reply0" to ByteArray(20) { 2 }), listOf(ByteArray(16) { 9 }), emptyList()), digest)
        assertTrue(store.ready("sim1", digest))
        assertEquals(50 * 1600, store.clips("sim1").greeting()!!.size)
    }

    /** The owner's voice, then a set spoken in it on the job thread (SCREENING_ASSISTANT.md 12.2). */
    @Test
    fun aSetIsSpokenInTheOwnersVoice() {
        val models = dev.cloudsim.gateway.screening.voice.VoiceModels(tmp.newFolder("models"), null)
        java.io.File(models.zipvoice.apply { mkdirs() }, "encoder.int8.onnx").writeText("x")
        java.io.File(models.zipvoice, "decoder.int8.onnx").writeText("x")
        java.io.File(models.zipvoice, "tokens.txt").writeText("x")
        models.vocoder.writeText("x")
        models.denoiser.writeText("x")
        val profile = dev.cloudsim.gateway.screening.voice.VoiceProfile(tmp.newFolder("voice"))
        val spoken = mutableListOf<String>()
        val jobs = dev.cloudsim.gateway.screening.voice.SpeechJobs(models, profile, { text, _, _, _ -> spoken += text; ShortArray(1600) },
            tmp.newFolder("cache"), { false })
        val requests = ScreeningRequests(client, RequestsSealer(), { owner }, store, { key }, { b -> ShortArray(b.size * 1600) },
            voice = ScreeningRequests.VoiceSetup(profile, jobs, models) { ShortArray(24000 * 5) })
        fun send(payload: JSONObject) {
            val id = "screening-${n++}-0000"
            requests.onRequest(JSONObject().put("device_id", owner).put("request_id", id).put("public_key", b64(ownerKey.publicKey))
                .put("envelope", JSONObject().put("kind", "screening").put("signer", b64(ownerKey.publicKey)).put("plain", payload.put("request_id", id).toString()).toString()))
        }
        val voice = JSONObject().put("audio", b64(ByteArray(10))).put("text", "您好，我是机主的自动接听助手。").toString().toByteArray()
        send(JSONObject().put("op", "voice").put("number", "_voice").put("set", "voice-00000001").put("index", 0).put("count", 1).put("chunk", b64(voice)))
        val voiceId = answer().getString("voice")
        val texts = JSONObject().put("greeting", "您好").put("hold", "请稍候").put("reply0", "请发短信")
        val bundle = JSONObject().put("texts", texts).put("voice", voiceId).toString().toByteArray()
        send(JSONObject().put("op", "asset").put("number", "sim1").put("set", "set-00000009").put("index", 0).put("count", 1).put("chunk", b64(bundle)))
        val started = answer()
        assertTrue(started.getBoolean("generating"))
        val digest = started.getString("digest")
        assertEquals(ScreenAssetStore.textDigest(voiceId, mapOf("greeting" to "您好", "hold" to "请稍候", "reply0" to "请发短信"), emptyList(), emptyList()), digest)
        // The job thread finishes, then the set is in force under the digest the iPhone signs.
        repeat(50) { if (jobs.progress("sim1")?.digest == null) Thread.sleep(20) }
        send(JSONObject().put("op", "status").put("number", "sim1"))
        assertEquals(digest, answer().getString("digest"))
        // The set in place, for the iPhone to skip sending it again when only switches change.
        assertEquals(digest, answer().getString("installed"))
        assertTrue(store.ready("sim1", digest))
        assertEquals(setOf("您好", "请稍候", "请发短信"), spoken.toSet())
    }

    /** What was said so far in one answer, then each new line under "<request>-live" while the call goes on. */
    @Test
    fun theOwnerFollowsACallsTranscript() {
        val t = dev.cloudsim.gateway.screening.transcript.Transcripts(tmp.newFolder("t"))
        val requests = ScreeningRequests(client, RequestsSealer(), { owner }, store, { key }, { b -> ShortArray(b.size * 1600) }, transcripts = t)
        val call = "0123abcd-0001"
        t.begin(call)
        t.add(call, "assistant", "您好")
        val id = "screening-${n++}-0000"
        requests.onRequest(JSONObject().put("device_id", owner).put("request_id", id).put("public_key", b64(ownerKey.publicKey))
            .put("envelope", JSONObject().put("kind", "screening").put("signer", b64(ownerKey.publicKey))
                .put("plain", JSONObject().put("op", "transcript").put("call_id", call).put("request_id", id).toString()).toString()))
        val first = client.delivered.last()
        assertEquals(id, first.getString("request_id"))
        assertTrue(first.getJSONArray("items").getJSONObject(0).getBoolean("live"))
        assertEquals("您好", first.getJSONArray("items").getJSONObject(0).getJSONArray("entries").getJSONObject(0).getString("text"))
        t.add(call, "caller", "我是快递")
        repeat(100) { if (client.delivered.last().getString("request_id") != "$id-live") Thread.sleep(10) }
        val live = client.delivered.last()
        assertEquals("$id-live", live.getString("request_id"))
        assertEquals("我是快递", live.getJSONArray("items").getJSONObject(0).getJSONObject("entry").getString("text"))
    }

    /** Family read a call's conversation with the answer right on its number, and may do nothing else (13.3). */
    @Test
    fun familyWithTheAnswerRightReadACallsTranscriptOnly() {
        val t = dev.cloudsim.gateway.screening.transcript.Transcripts(tmp.newFolder("t"))
        val familyKey = SoftwareSigner()
        val family = Protocol.deviceId(familyKey.publicKey)
        val rights = mapOf("sim1" to dev.cloudsim.gateway.calls.Rights(answer = true), "sim2" to dev.cloudsim.gateway.calls.Rights())
        val policy = dev.cloudsim.gateway.calls.SharingPolicy(1, listOf(dev.cloudsim.gateway.calls.Member(family, familyKey.publicKey, answer = true, perNumber = rights)),
            listOf(owner), "simultaneous", 15, null, false)
        val numbers = mapOf("0123abcd-0001" to "sim1", "0123abcd-0002" to "sim2")
        val requests = ScreeningRequests(client, RequestsSealer(), { owner }, store, { key }, { b -> ShortArray(b.size * 1600) }, transcripts = t,
            policy = { policy }, numberOf = { numbers[it] })
        numbers.keys.forEach { t.begin(it); t.add(it, "assistant", "您好"); t.end(it) }
        fun ask(op: String, call: String, signer: SoftwareSigner = familyKey) {
            val id = "screening-${n++}-0000"
            requests.onRequest(JSONObject().put("device_id", Protocol.deviceId(signer.publicKey)).put("request_id", id).put("public_key", b64(signer.publicKey))
                .put("envelope", JSONObject().put("kind", "screening").put("signer", b64(signer.publicKey))
                    .put("plain", JSONObject().put("op", op).put("call_id", call).put("request_id", id).toString()).toString()))
        }
        ask("transcript", "0123abcd-0001")
        assertEquals(1, client.delivered.size)
        assertEquals("您好", answer().getJSONArray("entries").getJSONObject(0).getString("text"))
        // No answer right on the number the call was on, or a call not known here: told so, with nothing in it.
        for (call in listOf("0123abcd-0002", "0123abcd-0003")) {
            ask("transcript", call)
            assertEquals("no_right", answer().getString("error"))
            assertEquals(0, answer().getJSONArray("entries").length())
        }
        // Anything but the conversation, or a phone not in the policy at all: not answered.
        ask("key", "0123abcd-0001")
        ask("transcript", "0123abcd-0001", SoftwareSigner())
        assertEquals(3, client.delivered.size)
    }

    @Test
    fun aGreetingTooLongIsRefused() {
        val bundle = JSONObject().put("clips", JSONObject().put("greeting", b64(ByteArray(200)))).toString().toByteArray()
        send(JSONObject().put("op", "asset").put("number", "sim1").put("set", "set-00000002").put("index", 0).put("count", 1).put("chunk", b64(bundle)))
        assertEquals("too_long", answer().getString("error"))
    }
}

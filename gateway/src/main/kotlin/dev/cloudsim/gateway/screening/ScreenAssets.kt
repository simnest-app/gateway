package dev.cloudsim.gateway.screening

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * One number's screening material from the owner's iPhone (SCREENING_ASSISTANT.md 10.1): the greeting, hold and
 * goodbye clips, the replies, and the allow list and contact digests. Arrives sealed by the owner; used only while its
 * digest is the one the owner signed in the policy. Clips are kept decoded (16 kHz mono PCM) in the app's private files.
 */
class ScreenAssetStore(private val dir: File) {
    private class Loaded(val digest: String, val clips: Map<String, ShortArray>, val allow: Set<String>, val contacts: Set<String>,
                         val texts: Map<String, String> = emptyMap())

    private val cache = HashMap<String, Loaded?>()

    /**
     * Installs a complete set for number [key]: the clips as sent ([encoded], AAC) and as decoded here ([clips]). The
     * digest is computed here over what was sent, never taken from the sender.
     */
    @Synchronized
    fun install(key: String, encoded: Map<String, ByteArray>, clips: Map<String, ShortArray>, allow: List<ByteArray>, contacts: List<ByteArray>): String {
        require(encoded.keys == clips.keys)
        val d = digest(encoded, allow, contacts)
        val target = File(dir, safe(key)).apply { deleteRecursively(); mkdirs() }
        for ((name, pcm) in clips) {
            require(name in NAMES || REPLY.matches(name))
            File(target, "$name.pcm").writeBytes(bytes(pcm))
        }
        val manifest = JSONObject().put("digest", d).put("clips", JSONArray(clips.keys.sorted()))
            .put("allow", JSONArray(allow.map(::b64))).put("contacts", JSONArray(contacts.map(::b64)))
        File(target, "manifest.json").writeText(manifest.toString())
        cache.remove(key)
        return d
    }

    /**
     * Installs a set the gateway spoke itself in the owner's voice (SCREENING_ASSISTANT.md 12.2): [digest] is over what
     * was asked for (see [textDigest]), which the owner's iPhone computes the same way.
     */
    @Synchronized
    fun installSpoken(key: String, digest: String, clips: Map<String, ShortArray>, allow: List<ByteArray>, contacts: List<ByteArray>,
                      texts: Map<String, String> = emptyMap()): String {
        val target = File(dir, safe(key)).apply { deleteRecursively(); mkdirs() }
        for ((name, pcm) in clips) {
            require(name in NAMES || REPLY.matches(name))
            File(target, "$name.pcm").writeBytes(bytes(pcm))
        }
        val manifest = JSONObject().put("digest", digest).put("clips", JSONArray(clips.keys.sorted()))
            .put("allow", JSONArray(allow.map(::b64))).put("contacts", JSONArray(contacts.map(::b64)))
            .put("texts", JSONObject(texts.filterKeys { it in clips }))
        File(target, "manifest.json").writeText(manifest.toString())
        cache.remove(key)
        return digest
    }

    @Synchronized
    fun remove(key: String) {
        File(dir, safe(key)).deleteRecursively()
        cache.remove(key)
    }

    /** The digest of [key]'s set in place, or null: the iPhone skips sending a set the gateway already has. */
    @Synchronized
    fun installed(key: String): String? = load(key)?.digest

    /** The words of a clip spoken in the owner's voice (for the call's transcript); null for clips made on the iPhone. */
    @Synchronized
    fun text(key: String, name: String): String? = load(key)?.texts?.get(name)

    /** The set for [key] if it is the one the policy names ([digest]) and has a greeting. */
    @Synchronized
    fun ready(key: String, digest: String): Boolean = digest.isNotEmpty() && load(key)?.let { it.digest == digest && "greeting" in it.clips } == true

    fun clips(key: String): ScreenClips = object : ScreenClips {
        override fun greeting() = clip("greeting")
        override fun hold() = clip("hold")
        override fun closing() = clip("closing")
        override fun reply(index: Int) = clip("reply$index")
        private fun clip(name: String) = synchronized(this@ScreenAssetStore) { load(key)?.clips?.get(name) }
    }

    /** Whether [digest] (of the caller's number) is on [key]'s allow list, or in the owner's contacts when [contacts]. */
    @Synchronized
    fun known(key: String, digest: String, contacts: Boolean): Known? {
        val l = load(key) ?: return null
        return when {
            digest in l.allow -> Known.ALLOWED
            contacts && digest in l.contacts -> Known.KNOWN
            else -> null
        }
    }

    private fun load(key: String): Loaded? = cache.getOrPut(key) {
        runCatching {
            val d = File(dir, safe(key))
            val m = JSONObject(File(d, "manifest.json").readText())
            val names = m.getJSONArray("clips").let { a -> (0 until a.length()).map(a::getString) }
            Loaded(m.getString("digest"), names.associateWith { pcm(File(d, "$it.pcm").readBytes()) },
                m.getJSONArray("allow").let { a -> (0 until a.length()).map(a::getString).toSet() },
                m.getJSONArray("contacts").let { a -> (0 until a.length()).map(a::getString).toSet() },
                m.optJSONObject("texts")?.let { t -> t.keys().asSequence().associateWith { t.getString(it) } } ?: emptyMap())
        }.getOrNull()
    }

    companion object {
        val NAMES = setOf("greeting", "hold", "closing")
        val REPLY = Regex("^reply[0-7]$")

        /**
         * The set's digest, as the owner's iPhone computes it for the policy: SHA-256 over each clip's name and its
         * encoded bytes in name order, then the sorted allow-list and contact digests.
         */
        fun digest(clips: Map<String, ByteArray>, allow: List<ByteArray>, contacts: List<ByteArray>): String {
            val md = MessageDigest.getInstance("SHA-256")
            for (name in clips.keys.sorted()) {
                md.update("clip:$name\n".toByteArray())
                md.update(clips.getValue(name))
            }
            for ((label, list) in listOf("allow" to allow, "contacts" to contacts)) {
                md.update("$label\n".toByteArray())
                list.map(::b64).sorted().forEach { md.update(it.toByteArray()); md.update(byteArrayOf(10)) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * The digest of a set the gateway speaks: SHA-256 over the voice's id, each line's name and words in name order,
         * then the sorted allow-list and contact digests. The owner's iPhone signs this into the policy.
         */
        fun textDigest(voiceId: String, texts: Map<String, String>, allow: List<ByteArray>, contacts: List<ByteArray>): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update("voice:$voiceId\n".toByteArray())
            for (name in texts.keys.sorted()) {
                md.update("text:$name\n".toByteArray())
                md.update(texts.getValue(name).toByteArray())
                md.update(byteArrayOf(10))
            }
            for ((label, list) in listOf("allow" to allow, "contacts" to contacts)) {
                md.update("$label\n".toByteArray())
                list.map(::b64).sorted().forEach { md.update(it.toByteArray()); md.update(byteArrayOf(10)) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** A caller's number as the lists hold it: HMAC-SHA256 with this gateway's key, first 16 bytes, base64. */
        fun numberDigest(key: ByteArray, e164: String): String =
            b64(Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(e164.toByteArray()).copyOf(16))

        fun bytes(pcm: ShortArray): ByteArray = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { asShortBuffer().put(pcm) }.array()

        fun pcm(b: ByteArray): ShortArray = ShortArray(b.size / 2).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }

        private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

        private fun safe(key: String) = if (key == "*") "_default" else key.replace(Regex("[^A-Za-z0-9_-]"), "_")
    }
}

/**
 * A caller's number in international form (+ and country code), the form the owner's lists use. Numbers already
 * international are kept; for a gateway in China, 11-digit mobiles and 0-prefixed landlines get +86. Others (service
 * numbers like 95588) stay as they are.
 */
fun e164(raw: String, country: String = "CN"): String {
    val n = raw.filter { it.isDigit() || it == '+' }
    return when {
        n.startsWith("+") -> n
        n.startsWith("00") -> "+" + n.drop(2)
        country == "CN" && n.length == 11 && n.startsWith("1") -> "+86$n"
        country == "CN" && n.startsWith("0") && n.length in 10..12 -> "+86" + n.drop(1)
        else -> n
    }
}

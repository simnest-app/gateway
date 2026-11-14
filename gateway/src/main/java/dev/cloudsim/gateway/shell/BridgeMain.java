package dev.cloudsim.gateway.shell;

import android.app.Application;
import android.app.Instrumentation;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Looper;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;

/**
 * The gateway's audio bridge (docs/architecture/MEDIA_3B.md). Runs as the shell user under app_process with this APK on
 * the class path, because only shell may capture the call downlink and write to Telephony TX. It takes commands only from
 * the gateway app, over a loopback TCP port (SELinux does not let an app connect to a shell Unix socket): it checks the
 * connecting socket's uid in /proc/net/tcp, and proves itself to the app with a token the app wrote to its external
 * files directory, which only the app and shell can read. It carries one call's media at a time:
 * the call downlink goes to the phone, the phone's audio goes into the call uplink, and while it runs the earpiece and
 * speaker are at volume 0 and the microphone is muted. Never opens the microphone, never plays anything on this phone.
 *
 * Args: the gateway app's uid, and the app's native library directory.
 * Shell identity code adapted from the scrcpy project (Apache-2.0, assets/licenses/scrcpy-LICENSE.txt), as in the stage 3 lab.
 */
public final class BridgeMain {
    public static final int PORT = 47710;
    private static final String TOKEN = "/sdcard/Android/data/dev.cloudsim.gateway/files/bridge-token";
    /** Where the app keeps its token: the app says (its package name is not fixed in here); an older app gives no third argument. */
    private static volatile String tokenPath = TOKEN;
    private static final String SHELL = "com.android.shell";

    /**
     * HiSilicon phones (Huawei, Honor) handle call audio differently from the Redmi. Measured on a Mate 40 Pro+
     * (HarmonyOS 4.2 / Android 12, 2026-11-10), with a second phone on the call recording what arrived:
     *
     * - The uplink only reaches the far end while the track to Telephony TX is the one track playing. Any other track
     *   on the phone, even one that only carries zeros, takes the route to the earpiece and nothing arrives. The call
     *   library keeps such a track for the audio it receives, so on these phones it is swapped for [PacedSink].
     * - That track reports the earpiece as its route until sound is written to it, so it is written without waiting
     *   for the route (the earpiece and speaker are at volume 0 as everywhere).
     * - VOICE_DOWNLINK gives a second of sound and then silence; the input only has profiles for both directions.
     *   VOICE_CALL in stereo has the far end, and nothing of what is written to the uplink. It has to be recording
     *   before the uplink track is opened: opened the other way round, the recording reads zeros.
     * - The uplink track left to run dry loses Telephony TX (every phone is kept fed with silence for the pacer's sake).
     * - With the recording running, muting the microphone before the uplink opens leaves the uplink intact, and once
     *   the uplink is open the microphone is not in the call at all. (Without a recording, muting it mutes the uplink.)
     */
    private static final boolean HISILICON_UPLINK =
        java.util.Arrays.asList("HUAWEI", "HONOR").contains(android.os.Build.MANUFACTURER.toUpperCase(java.util.Locale.ROOT));
    /** After a connected call loses ICE, the phone has this long to restart it before the bridge ends the media. */
    private static final long RECOVERY_MS = 10_000;
    /**
     * For a phone that is asking again (it restarted ICE within the last RESTART_RECENT_MS): it is there and
     * reconnecting, and ends the call itself when it gives up, at 10 s, or at 20 s with a VPN on, which reconnects
     * first after a change of network (ANSWER_DELAY.md 10.25). A phone that has gone silent is still dropped at
     * RECOVERY_MS. "A newer offer than the last connected moment" did not tell: the offer comes the moment the
     * phone's network changes, seconds before this side sees the path gone (10.27).
     */
    private static final long RECOVERY_ASKING_MS = 21_000;
    private static final long RESTART_RECENT_MS = 30_000;

    private static Context context;
    /**
     * One factory and audio module for the life of the process: a new pair per call left WebRTC threads behind
     * (AudioDeviceBuffer, rtc_event_log) and uplink drops grew with each call in the same process, up to 16%.
     */
    private static JavaAudioDeviceModule adm;
    private static PeerConnectionFactory factory;
    private static volatile Session session;
    /**
     * Earpiece silence started at a remote answer (or a dial), before the first offer; the session takes it over.
     * Re-asserted every 200 ms meanwhile (QuietHold); two minutes without media gives the volume back.
     */
    private static final QuietHold<Silence> pendingQuiet = new QuietHold<>(200, 120_000);
    /** At most 1 s of a phone's audio waiting to be played (50 packets of 20 ms), ANSWER_DELAY.md 8.7. */
    static final int JITTER_BUFFER_PACKETS = 50;
    /** When the last offer reached this process, to time the answer from it. */
    private static volatile long offerReceivedAt;

    /** Wall-clock time of day, to line this log up with the app's and the phone's. */
    private static String clock() {
        return new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT).format(new java.util.Date());
    }
    /** A call whose recording was asked for before its media started (RECORDING_V7.md): the session starts it. */
    private static String pendingRecord;
    /** A recording notice handed over before its call's media started (RECORDING_NOTICE.md 8): the session plays it. */
    private static PendingNotice pendingNotice;

    /** The pieces of a notice waiting for the call's media. */
    private static final class PendingNotice {
        final String callId;
        final List<short[]> pieces = new ArrayList<>();
        String end;
        PendingNotice(String callId) { this.callId = callId; }
    }
    private static volatile Writer out;

    public static void main(String[] args) throws Exception {
        int appUid = Integer.parseInt(args[0]);
        if (args.length > 2) tokenPath = args[2];
        Looper.prepareMainLooper();
        context = installShellIdentity();
        System.load(args[1] + "/libjingle_peerconnection_so.so");
        PortMap.load(args[1]);
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
            .setNativeLibraryLoader(name -> true).createInitializationOptions());
        warmTx();
        java.net.ServerSocket server = new java.net.ServerSocket(PORT, 1, java.net.InetAddress.getLoopbackAddress());
        // The version and the commit it was built from: evidence taken from the log names the code that ran.
        System.out.println("bridge ready port=" + PORT + " app_uid=" + appUid + " version=" + dev.cloudsim.gateway.BuildConfig.VERSION_NAME
            + " build=" + dev.cloudsim.gateway.BuildConfig.BUILD_COMMIT);
        while (true) {
            java.net.Socket peer = server.accept();
            int uid = socketUid(peer.getPort());
            if (uid != appUid) {
                System.out.println("refused uid=" + uid);
                peer.close();
                continue;
            }
            serve(peer);
        }
    }

    /** The uid that owns the loopback connection from this local port, from the kernel's socket tables. */
    static int socketUid(int port) {
        for (String table : new String[]{"/proc/net/tcp6", "/proc/net/tcp"}) {
            try (BufferedReader r = new BufferedReader(new java.io.FileReader(table))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] f = line.trim().split("\\s+");
                    if (f.length < 8 || !f[1].contains(":")) continue;
                    String local = f[1], remote = f[2];
                    if (Integer.parseInt(local.substring(local.indexOf(':') + 1), 16) == port
                            && Integer.parseInt(remote.substring(remote.indexOf(':') + 1), 16) == PORT) return Integer.parseInt(f[7]);
                }
            } catch (Exception e) {
                System.out.println("socket table " + table + " " + e);
            }
        }
        return -1;
    }

    /** Proof that this is the bridge the app started: HMAC of the app's challenge with the token only the app and shell can read. */
    private static String proof(String nonce) throws Exception {
        byte[] token = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(tokenPath));
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(token, "HmacSHA256"));
        return android.util.Base64.encodeToString(mac.doFinal(("cloudsim-bridge-v1" + nonce).getBytes("UTF-8")), android.util.Base64.NO_WRAP);
    }

    /** One app connection at a time; when it drops, any media stops, so a dead app cannot leave the phone silenced. */
    private static void serve(java.net.Socket peer) {
        System.out.println("app connected");
        try (java.net.Socket socket = peer) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            out = new OutputStreamWriter(socket.getOutputStream(), "UTF-8");
            JSONObject hello = new JSONObject(in.readLine());
            // The class path tells the app which APK this bridge runs from, so it can restart it after an update.
            send(new JSONObject().put("type", "ready").put("proof", proof(hello.getString("nonce"))).put("classpath", System.getProperty("java.class.path")));
            String line;
            while ((line = in.readLine()) != null) handle(new JSONObject(line));
        } catch (Exception e) {
            System.out.println("app connection " + e);
        } finally {
            out = null;
            stopSession("app_disconnected");
            releaseQuiet();
            System.out.println("app disconnected");
        }
    }

    private static void handle(JSONObject m) throws Exception {
        String type = m.getString("type"), callId = m.optString("call_id");
        switch (type) {
            case "offer": {
                offerReceivedAt = SystemClock.elapsedRealtime();
                Session s = session;
                if (s != null && !s.callId.equals(callId)) stopSession("replaced");
                try {
                    if (session == null) session = new Session(callId, m.optJSONObject("ice"), m.optJSONObject("cellular"));
                    // A phone joining a screened call to listen in (SCREENING_ASSISTANT.md 10.3) is not heard until it takes over.
                    if (m.optBoolean("listen")) session.listen(m.optString("member", DEFAULT_MEMBER), true);
                    session.offer(m.optString("member", DEFAULT_MEMBER), m.getInt("gen"), m.getString("sdp"), m.optJSONObject("ice"));
                } catch (Exception e) {
                    System.out.println("offer failed " + e);
                    // Only the phone whose offer failed leaves; the call's media ends with its last phone.
                    if (session != null) session.leave(m.optString("member", DEFAULT_MEMBER), "offer_failed");
                    if (session != null && session.members.isEmpty()) stopSession("offer_failed");
                    else send(new JSONObject().put("type", "ended").put("call_id", callId).put("reason", "start_failed"));
                }
                break;
            }
            case "quiet":
                // A phone answered (or a dial began): silence the earpiece now rather than when the first offer
                // arrives, and keep it silent until then. Sent again when the call goes active: asserted again at once.
                synchronized (BridgeMain.class) {
                    Session s = session;
                    if (s != null) s.quiet.enforce();
                    else try { pendingQuiet.quiet(Silence::enable); } catch (Exception e) { System.out.println("quiet failed " + e); }
                }
                break;
            case "leave": {
                // One phone of a multi-party call is done; the call's media goes on for the others.
                Session s = session;
                if (s != null && s.callId.equals(callId)) s.leave(m.optString("member", DEFAULT_MEMBER), m.optString("reason", "left"));
                break;
            }
            case "record": {
                // Start or stop this call's recording (RECORDING_V7.md); before its media starts, remembered for it.
                boolean on = m.optBoolean("on");
                Session s = session;
                if (s != null && s.callId.equals(callId)) s.record(on);
                else synchronized (BridgeMain.class) { pendingRecord = on ? callId : (callId.equals(pendingRecord) ? null : pendingRecord); }
                break;
            }
            case "notice": {
                // A piece of the recording notice for the far party, 16 kHz mono; "end" names it once its last piece
                // is in (RECORDING_NOTICE.md 8). Before the call's media starts, kept for it.
                byte[] raw = android.util.Base64.decode(m.optString("data"), android.util.Base64.NO_WRAP);
                short[] pcm = new short[raw.length / 2];
                ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
                String end = m.has("end") ? m.optString("end") : null;
                Session s = session;
                if (s != null && s.callId.equals(callId)) s.notice(pcm, end);
                else synchronized (BridgeMain.class) {
                    if (pendingNotice == null || !pendingNotice.callId.equals(callId)) pendingNotice = new PendingNotice(callId);
                    pendingNotice.pieces.add(pcm);
                    if (end != null) pendingNotice.end = end;
                }
                break;
            }
            case "notice_stop": {
                synchronized (BridgeMain.class) { if (pendingNotice != null && pendingNotice.callId.equals(callId)) pendingNotice = null; }
                Session s = session;
                if (s != null && s.callId.equals(callId)) s.noticeStop();
                break;
            }
            case "stop":
                synchronized (BridgeMain.class) {
                    if (callId.equals(pendingRecord)) pendingRecord = null;
                    if (pendingNotice != null && pendingNotice.callId.equals(callId)) pendingNotice = null;
                }
                if (session != null && session.callId.equals(callId)) stopSession("stopped");
                releaseQuiet();
                break;
            case "assist": {
                // The screening assistant answers or leaves (SCREENING_ASSISTANT.md 10.3); answering needs no phone.
                boolean on = m.optBoolean("on");
                Session s = session;
                if (s != null && !s.callId.equals(callId)) { if (!on) break; stopSession("replaced"); s = null; }
                try {
                    if (s == null) {
                        if (!on) break;
                        s = new Session(callId, null, m.optJSONObject("cellular"));
                        session = s;
                    }
                    s.assist(on);
                    send(new JSONObject().put("type", "assisting").put("call_id", callId).put("on", on));
                } catch (Exception e) {
                    System.out.println("assist failed " + e);
                    send(new JSONObject().put("type", "assisting").put("call_id", callId).put("on", false).put("error", String.valueOf(e.getMessage())));
                    if (session != null && session.members.isEmpty() && session.assistant == null) stopSession("assist_failed");
                }
                break;
            }
            case "say": {
                // A piece of what the assistant says, 16 kHz mono; "end" names the utterance once its last piece is in.
                Session s = session;
                if (s == null || !s.callId.equals(callId)) break;
                byte[] raw = android.util.Base64.decode(m.optString("data"), android.util.Base64.NO_WRAP);
                short[] pcm = new short[raw.length / 2];
                ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
                s.say(pcm, m.has("end") ? m.optString("end") : null);
                break;
            }
            case "hush": {
                Session s = session;
                if (s != null && s.callId.equals(callId)) s.hush();
                break;
            }
            case "listen": {
                Session s = session;
                if (s != null && s.callId.equals(callId)) s.listen(m.optString("member", DEFAULT_MEMBER), m.optBoolean("on"));
                break;
            }
            case "mapping":
                // The owner's port-mapping switch (DIRECT_PATHS.md 4), sent by the app when it connects and when changed.
                PortMap.set(m.optBoolean("on"));
                break;
            case "warm":
                if (session == null) warmTx();
                break;
            case "probe": {
                // A connection self-test (SELF_TEST.md): never alongside a call's media.
                String id = m.getString("probe_id");
                if (session != null) { send(new JSONObject().put("type", "probe_failed").put("probe_id", id).put("reason", "busy")); break; }
                JSONObject ice = m.optJSONObject("ice");
                String sdp = m.getString("sdp");
                JSONObject cellular = m.optJSONObject("cellular");
                boolean file = m.optBoolean("file");
                new Thread(() -> Probe.run(id, sdp, ice, cellular, file), "probe").start();
                break;
            }
            case "file_chunk": {
                // A piece of a recording being downloaded, as the app read it for the phone (RECORDING_V7.md 5).
                ArrayBlockingQueue<JSONObject> q = Probe.chunks.get(m.optString("probe_id"));
                if (q != null) q.offer(m);
                break;
            }
            default:
                System.out.println("unknown command " + type);
        }
    }

    interface JsonMaker { JSONObject make() throws Exception; }

    /** A message built where org.json's checked exception is in the way; null (nothing sent) if it fails. */
    static JSONObject safe(JsonMaker f) {
        try { return f.make(); } catch (Exception e) { return null; }
    }

    static synchronized void send(JSONObject m) {
        Writer w = out;
        if (w == null || m == null) return;
        try {
            w.write(m.toString() + "\n");
            w.flush();
        } catch (Exception e) {
            System.out.println("send failed " + e);
        }
    }

    static synchronized void releaseQuiet() {
        pendingQuiet.release();
    }

    /** Hands over silence started at the answer, so its saved normal volume is the one restored. */
    static synchronized Silence takeQuiet() throws Exception {
        Silence s = pendingQuiet.take();
        return s != null ? s : Silence.enable();
    }

    static synchronized void stopSession(String reason) {
        Session s = session;
        if (s == null) return;
        session = null;
        s.close(reason);
    }

    /**
     * The uplink only works reliably in a call if Telephony TX was written to before it (stage 1 finding): 3 s of silence
     * after start and after each call keeps it ready for the next.
     */
    private static void warmTx() {
        new Thread(() -> {
            android.media.AudioTrack track = null;
            try {
                track = telephonyTrack(48000);
                track.play();
                short[] quiet = new short[480];
                long end = SystemClock.elapsedRealtime() + 3000;
                while (SystemClock.elapsedRealtime() < end && session == null) track.write(quiet, 0, quiet.length);
                System.out.println("tx warmed");
            } catch (Exception e) {
                System.out.println("tx warm failed " + e);
            } finally {
                if (track != null) track.release();
            }
        }, "warm").start();
    }

    /** Most phones in one call (MULTIPARTY_TRANSFER_V6.md): one cellular party and up to three CloudSim phones. */
    static final int MAX_MEMBERS = 3;
    /** The member an offer without one belongs to: the gateway app before multi-party sends none. */
    static final String DEFAULT_MEMBER = "phone";
    /** The screening assistant's place in the uplink mix (SCREENING_ASSISTANT.md 10.3); never a phone's member ID. */
    static final String ASSISTANT = "assistant";
    /** The recording notice's place in the uplink mix (RECORDING_NOTICE.md 8); never a phone's member ID. */
    static final String NOTICE = "notice";
    /** The uplink has to have been live this long before the notice starts, it may wait this long for that, and this much quiet follows it. */
    static final long NOTICE_LEAD_MS = 300, NOTICE_WAIT_MS = 8000, NOTICE_TAIL_MS = 300;

    /** 10 ms at 16 kHz to 10 ms at [rate], linearly: the assistant's speech joins the phones' audio at their rate. */
    static short[] up(short[] in, int rate) {
        int n = in.length * rate / 16000;
        short[] out = new short[n];
        if (in.length == 0) return out;
        for (int i = 0; i < n; i++) {
            double t = i * 16000.0 / rate;
            int k = (int) t;
            double f = t - k;
            short a = in[Math.min(k, in.length - 1)], b = in[Math.min(k + 1, in.length - 1)];
            out[i] = (short) Math.round(a + (b - a) * f);
        }
        return out;
    }

    interface Candidate { void add(IceCandidate c); }

    /** Hands each candidate to the other end of a loopback pair; nothing else matters there. */
    static PeerConnection.Observer loopbackObserver(Candidate other) {
        return new PeerConnection.Observer() {
            public void onIceCandidate(IceCandidate c) { other.add(c); }
            public void onIceConnectionChange(PeerConnection.IceConnectionState s) {}
            public void onSignalingChange(PeerConnection.SignalingState s) {}
            public void onIceConnectionReceivingChange(boolean b) {}
            public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
            public void onIceCandidatesRemoved(IceCandidate[] c) {}
            public void onAddStream(MediaStream m) {}
            public void onRemoveStream(MediaStream m) {}
            public void onDataChannel(DataChannel d) {}
            public void onRenegotiationNeeded() {}
            public void onTrack(RtpTransceiver t) {}
        };
    }

    /**
     * One call's media (MULTIPARTY_TRANSFER_V6.md, 5a2): the call downlink as one local track sent to every phone, the
     * phones' audio summed into the call uplink, and the earpiece silence; each phone is a member with its own peer
     * connection, offers and ICE state. A member that stays disconnected past the recovery window leaves alone; the
     * call's media ends when the last member does.
     */
    /** One ICE server per relay, each with its own credentials (IceList). */
    static List<PeerConnection.IceServer> iceServers(JSONObject ice) {
        List<PeerConnection.IceServer> servers = new ArrayList<>();
        for (IceList.Entry e : IceList.parse(ice)) {
            servers.add(PeerConnection.IceServer.builder(e.urls).setUsername(e.username).setPassword(e.credential).createIceServer());
        }
        return servers;
    }

    static final class Session {
        final String callId;
        private AudioSource source;
        private AudioTrack track;
        private final Silence quiet;
        private JSONObject iceServers;
        private final java.util.Map<String, Member> members = new java.util.concurrent.ConcurrentHashMap<>();
        private final ArrayBlockingQueue<short[]> uplink = new ArrayBlockingQueue<>(50);
        private final UplinkMixer mixer;
        private final AtomicLong injected = new AtomicLong(), dropped = new AtomicLong();
        /** Where uplink audio is lost: the queue was full, or Telephony TX took only part of a block. */
        private final AtomicLong droppedQueue = new AtomicLong(), droppedPartial = new AtomicLong();
        private volatile android.media.AudioTrack txTrack;
        /** Output samples per input sample in the uplink resampler; 1 when TX keeps its nominal rate. */
        private volatile double txRatio = 1;
        private volatile long written;
        /** Frames, frames that were not silence, and peak level, for each direction: tells silence from no audio at all. */
        private final AtomicLong capFrames = new AtomicLong(), capNonzero = new AtomicLong(), capPeak = new AtomicLong();
        private final AtomicLong sinkFrames = new AtomicLong(), sinkNonzero = new AtomicLong(), sinkPeak = new AtomicLong();
        /** Samples at or near full scale, and all samples, in the phones' audio: how much of it arrives clipped. */
        private final AtomicLong sinkClipped = new AtomicLong(), sinkSamples = new AtomicLong();
        private volatile int sinkRate, route = -1;
        private volatile String stop = "";

        void onCaptured(JavaAudioDeviceModule.AudioSamples s) {
            ByteBuffer data = ByteBuffer.wrap(s.getData());
            count(data, capFrames, capNonzero, capPeak);
            RecordTap t = tap;
            Assistant a = assistant;
            if ((t == null && a == null) || s.getAudioFormat() != AudioFormat.ENCODING_PCM_16BIT) return;
            ByteBuffer pcm = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            int channels = Math.max(1, s.getChannelCount()), frames = s.getData().length / 2 / channels;
            short[] mono = new short[frames];
            for (int i = 0; i < frames; i++) {
                int sum = 0;
                for (int c = 0; c < channels; c++) sum += pcm.getShort();
                mono[i] = (short) (sum / channels);
            }
            if (t != null) t.addFar(mono, frames, s.getSampleRate());
            if (a != null) a.far.addFar(mono, frames, s.getSampleRate());
        }

        /** The screening assistant while it is in the call (SCREENING_ASSISTANT.md 10.3); null otherwise. */
        private volatile Assistant assistant;
        /** Phones listening in without being heard: in the call, not in the uplink mix. */
        private final java.util.Set<String> listening = java.util.concurrent.ConcurrentHashMap.newKeySet();

        /**
         * The gateway's built-in assistant as a member of the call with no phone behind it (SCREENING_ASSISTANT.md 2.7,
         * S1-0 option A): a loopback pair of peer connections makes the shared audio module capture the far party, which
         * goes to the app as 16 kHz mono "far" blocks every 100 ms; what the app hands over with "say" is mixed into the
         * uplink, 10 ms at a time, silence in between, and "said" goes back once an utterance has been played out.
         */
        final class Assistant {
            private final PeerConnection sender, receiver;
            final RecordTap far = new RecordTap();
            /** 16 kHz blocks of 10 ms still to be played, and the utterance whose end is in the queue. */
            private final java.util.concurrent.LinkedBlockingQueue<short[]> speech = new java.util.concurrent.LinkedBlockingQueue<>();
            private final java.util.concurrent.LinkedBlockingQueue<String> ends = new java.util.concurrent.LinkedBlockingQueue<>();
            private volatile boolean running = true;
            private final short[] rest = new short[160];
            private int restLen;

            Assistant() throws Exception {
                PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(new ArrayList<>());
                config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
                PeerConnection[] pcs = new PeerConnection[2];
                pcs[0] = factory.createPeerConnection(config, loopbackObserver(c -> pcs[1].addIceCandidate(c)));
                pcs[1] = factory.createPeerConnection(config, loopbackObserver(c -> pcs[0].addIceCandidate(c)));
                sender = pcs[0];
                receiver = pcs[1];
                sender.addTransceiver(track, new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY));
                SessionDescription offer = await(o -> sender.createOffer(o, new MediaConstraints()));
                await(o -> sender.setLocalDescription(o, offer));
                await(o -> receiver.setRemoteDescription(o, offer));
                SessionDescription answer = await(o -> receiver.createAnswer(o, new MediaConstraints()));
                await(o -> receiver.setLocalDescription(o, answer));
                await(o -> sender.setRemoteDescription(o, answer));
                if (sinkRate == 0) sinkRate = 48000;
                mixer.add(ASSISTANT);
                new Thread(this::speak, "assistant-say").start();
                new Thread(this::hear, "assistant-far").start();
                System.out.println("assistant joined " + callId.substring(0, 8));
            }

            /** One piece of an utterance from the app; [end] names the utterance once its last piece is in. */
            synchronized void say(short[] pcm16k, String end) {
                int i = 0;
                if (restLen > 0) {
                    int n = Math.min(160 - restLen, pcm16k.length);
                    System.arraycopy(pcm16k, 0, rest, restLen, n);
                    restLen += n;
                    i = n;
                    if (restLen == 160) { speech.add(rest.clone()); restLen = 0; }
                }
                for (; i + 160 <= pcm16k.length; i += 160) speech.add(java.util.Arrays.copyOfRange(pcm16k, i, i + 160));
                if (i < pcm16k.length) { restLen = pcm16k.length - i; System.arraycopy(pcm16k, i, rest, 0, restLen); }
                if (end != null) {
                    if (restLen > 0) { speech.add(java.util.Arrays.copyOf(rest, 160)); restLen = 0; }
                    speech.add(new short[0]);
                    ends.add(end);
                }
            }

            /** Drops whatever is still to be said (the owner took over, or asked for another reply). */
            synchronized void hush() {
                speech.clear();
                restLen = 0;
                String id;
                while ((id = ends.poll()) != null) { final String said = id; send(safe(() -> new JSONObject().put("type", "said").put("call_id", callId).put("id", said).put("cut", true))); }
            }

            private void speak() {
                long next = SystemClock.elapsedRealtime();
                short[] quietBlock = new short[160];
                while (running && stop.isEmpty()) {
                    next += 10;
                    long wait = next - SystemClock.elapsedRealtime();
                    if (wait > 0) { try { Thread.sleep(wait); } catch (InterruptedException e) { return; } }
                    else if (wait < -500) next = SystemClock.elapsedRealtime();
                    short[] b = speech.poll();
                    while (b != null && b.length == 0) {
                        // The marker after an utterance's last piece: it has gone out.
                        String id = ends.poll();
                        if (id != null) send(safe(() -> new JSONObject().put("type", "said").put("call_id", callId).put("id", id)));
                        b = speech.poll();
                    }
                    mixer.mix(ASSISTANT, up(b == null ? quietBlock : b, sinkRate == 0 ? 48000 : sinkRate));
                }
            }

            private void hear() {
                long next = SystemClock.elapsedRealtime(), seq = 0;
                while (running && stop.isEmpty()) {
                    next += 100;
                    long wait = next - SystemClock.elapsedRealtime();
                    if (wait > 0) { try { Thread.sleep(wait); } catch (InterruptedException e) { return; } }
                    else if (wait < -1000) next = SystemClock.elapsedRealtime();
                    short[] both = far.tick();
                    ByteBuffer b = ByteBuffer.allocate(RecordTap.CHUNK * 2).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < RecordTap.CHUNK; i++) b.putShort(both[i * 2]);
                    final long n = seq++;
                    send(safe(() -> new JSONObject().put("type", "far").put("call_id", callId).put("seq", n)
                        .put("data", android.util.Base64.encodeToString(b.array(), android.util.Base64.NO_WRAP))));
                }
            }

            void close() {
                running = false;
                mixer.remove(ASSISTANT);
                hush();
                try { sender.close(); receiver.close(); sender.dispose(); receiver.dispose(); } catch (Exception ignored) {}
                System.out.println("assistant left " + callId.substring(0, 8));
            }
        }

        /** Starts or ends the assistant's part in this call. */
        synchronized void assist(boolean on) throws Exception {
            if (on && assistant == null) assistant = new Assistant();
            else if (!on && assistant != null) {
                Assistant a = assistant;
                assistant = null;
                a.close();
                // Handed to a phone that has yet to connect: the media (and the silenced earpiece and microphone) stay
                // until it does; the call's end stops them otherwise.
            }
        }

        void say(short[] pcm, String end) { Assistant a = assistant; if (a != null) a.say(pcm, end); }

        void hush() { Assistant a = assistant; if (a != null) a.hush(); }

        /** A phone listening in is heard in the call only once it stops listening (it took the call over). */
        void listen(String memberId, boolean on) {
            if (on) { listening.add(memberId); mixer.remove(memberId); }
            else if (listening.remove(memberId) && members.containsKey(memberId)) mixer.add(memberId);
        }

        /** The recording notice being played to the far party, or waiting for the uplink; null otherwise. */
        private volatile Announcer announcer;

        /**
         * Plays the recording notice to the far party before a recording starts (RECORDING_NOTICE.md 3 and 8): a member
         * of the uplink mix that gives silence until the uplink is really reaching the call, then the notice, the
         * phones turned down under it. "noticed" tells the app whether it went out; the recording starts only if it did.
         */
        final class Announcer {
            private final java.util.concurrent.LinkedBlockingQueue<short[]> speech = new java.util.concurrent.LinkedBlockingQueue<>();
            private final short[] rest = new short[160];
            private int restLen;
            private volatile String id;
            private volatile boolean cancelled;

            /** One piece of the notice; [end] names it once its last piece is in. */
            synchronized void add(short[] pcm16k, String end) {
                int i = 0;
                if (restLen > 0) {
                    int n = Math.min(160 - restLen, pcm16k.length);
                    System.arraycopy(pcm16k, 0, rest, restLen, n);
                    restLen += n;
                    i = n;
                    if (restLen == 160) { speech.add(rest.clone()); restLen = 0; }
                }
                for (; i + 160 <= pcm16k.length; i += 160) speech.add(java.util.Arrays.copyOfRange(pcm16k, i, i + 160));
                if (i < pcm16k.length) { restLen = pcm16k.length - i; System.arraycopy(pcm16k, i, rest, 0, restLen); }
                if (end != null) {
                    if (restLen > 0) { speech.add(java.util.Arrays.copyOf(rest, 160)); restLen = 0; }
                    id = end;
                }
            }

            void cancel() { cancelled = true; }

            /** The uplink is being written to the call: on HiSilicon the route shows only once there is sound. */
            private boolean live() {
                if (txTrack == null) return false;
                if (route == AudioDeviceInfo.TYPE_TELEPHONY) return true;
                return HISILICON_UPLINK && (route == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE || route == -1);
            }

            void run() {
                mixer.add(NOTICE);
                // On the phones' beat, not this thread's: see UplinkMixer.rider.
                mixer.ride(NOTICE);
                short[] quietBlock = new short[160];
                long began = SystemClock.elapsedRealtime(), next = began, liveSince = 0, spoken = 0, tail = 0, writtenBefore = 0;
                boolean speaking = false;
                String failed = null;
                try {
                    while (true) {
                        if (!stop.isEmpty()) { failed = "media"; break; }
                        if (cancelled) { failed = "cancelled"; break; }
                        next += 10;
                        long now = SystemClock.elapsedRealtime(), wait = next - now;
                        if (wait > 0) { try { Thread.sleep(wait); } catch (InterruptedException e) { failed = "interrupted"; break; } }
                        else if (wait < -500) next = now;
                        short[] b = quietBlock;
                        if (!speaking) {
                            if (live()) { if (liveSince == 0) liveSince = now; } else liveSince = 0;
                            if (id != null && liveSince != 0 && now - liveSince >= NOTICE_LEAD_MS) {
                                speaking = true;
                                writtenBefore = written;
                                mixer.solo(NOTICE);
                            } else if (now - began > NOTICE_WAIT_MS) { failed = id == null ? "incomplete" : "uplink"; break; }
                        }
                        // The mixer takes one block a tick of the phones: no more is handed to it than a few ahead.
                        if (mixer.waiting() >= 4) continue;
                        if (speaking) {
                            short[] piece = speech.poll();
                            if (piece != null) { b = piece; spoken++; }
                            // The last of it is still on its way through the pacer and the track: a moment of quiet after.
                            else if (mixer.waiting() == 0 && ++tail * 10 >= NOTICE_TAIL_MS) break;
                        }
                        mixer.mix(NOTICE, up(b, sinkRate == 0 ? 48000 : sinkRate));
                    }
                    // Said into an uplink that took nothing, or that left the call under it, is not said.
                    if (failed == null && written == writtenBefore) failed = "not_written";
                    if (failed == null && route != AudioDeviceInfo.TYPE_TELEPHONY) failed = "route_" + route;
                } finally {
                    mixer.solo(null);
                    mixer.remove(NOTICE);
                    synchronized (Session.this) { if (announcer == this) announcer = null; }
                }
                final String why = failed, name = id == null ? "" : id;
                System.out.println("notice " + callId.substring(0, 8) + (why == null ? " played" : " not played: " + why) + " blocks=" + spoken);
                send(safe(() -> {
                    JSONObject o = new JSONObject().put("type", "noticed").put("call_id", callId).put("id", name).put("ok", why == null);
                    if (why != null) o.put("reason", why);
                    return o;
                }));
            }
        }

        /** A piece of the recording notice: the first starts the announcer. */
        synchronized void notice(short[] pcm, String end) {
            Announcer a = announcer;
            if (a == null) {
                if (sinkRate == 0) sinkRate = 48000;
                final Announcer started = new Announcer();
                a = started;
                announcer = a;
                new Thread(started::run, "notice").start();
            }
            a.add(pcm, end);
        }

        void noticeStop() { Announcer a = announcer; if (a != null) a.cancel(); }

        /** The recording's two sides while it runs (RECORDING_V7.md 2); null when not recording. */
        private volatile RecordTap tap;

        /**
         * Starts or stops handing this call's audio to the app for a recording: every 100 ms one 16 kHz stereo block
         * (left the far party, right this side), as "pcm" lines; "pcm_end" when it stops, with why.
         */
        synchronized void record(boolean on) {
            if (!on) {
                if (tap == null) return;
                tap = null;
                send(safe(() -> new JSONObject().put("type", "pcm_end").put("call_id", callId).put("reason", "stopped")));
                System.out.println("recording " + callId.substring(0, 8) + " stopped");
                return;
            }
            if (tap != null) return;
            final RecordTap t = new RecordTap();
            tap = t;
            System.out.println("recording " + callId.substring(0, 8) + " started");
            new Thread(() -> {
                long next = SystemClock.elapsedRealtime(), seq = 0;
                while (tap == t && stop.isEmpty()) {
                    next += 100;
                    long wait = next - SystemClock.elapsedRealtime();
                    if (wait > 0) { try { Thread.sleep(wait); } catch (InterruptedException e) { return; } }
                    // Fell far behind (the process was paused): start counting from now rather than burst out silence.
                    else if (wait < -1000) next = SystemClock.elapsedRealtime();
                    short[] block = t.tick();
                    ByteBuffer b = ByteBuffer.allocate(block.length * 2).order(ByteOrder.LITTLE_ENDIAN);
                    for (short v : block) b.putShort(v);
                    final long n = seq++;
                    send(safe(() -> new JSONObject().put("type", "pcm").put("call_id", callId).put("seq", n)
                        .put("data", android.util.Base64.encodeToString(b.array(), android.util.Base64.NO_WRAP))));
                    if (n % 600 == 599) System.out.println("recording " + callId.substring(0, 8) + " blocks=" + (n + 1) + " far_missing=" + t.farMissing + " near_missing=" + t.nearMissing);
                }
            }, "record").start();
        }

        /** One phone in the call. */
        final class Member {
            final String id;
            final PeerConnection pc;
            volatile int generation = -1;
            volatile String ice = "NEW";
            volatile long lastConnected;
            /** When this phone last restarted ICE (an offer of a later generation), to tell one that is reconnecting from one that is gone. */
            volatile long lastRestart;
            volatile boolean announced;
            volatile CountDownLatch gathered = new CountDownLatch(1);
            /** Router port mappings for this phone's connection (DIRECT_PATHS.md 4), removed when it closes. */
            final List<Integer> mappings = new ArrayList<>();

            private double jbDelay;
            private long jbEmitted;

            /** How long this phone's audio waited in the receive buffer on average since the last reading. */
            void logJitterBuffer() {
                PeerConnection p = pc;
                if (p == null) return;
                p.getStats(report -> {
                    for (RTCStats st : report.getStatsMap().values()) {
                        if (!"inbound-rtp".equals(st.getType())) continue;
                        Object delay = st.getMembers().get("jitterBufferDelay"), emitted = st.getMembers().get("jitterBufferEmittedCount");
                        if (!(delay instanceof Number) || !(emitted instanceof Number)) continue;
                        double d = ((Number) delay).doubleValue();
                        long e = ((Number) emitted).longValue();
                        long ms = e > jbEmitted ? Math.round((d - jbDelay) / (e - jbEmitted) * 1000) : -1;
                        jbDelay = d;
                        jbEmitted = e;
                        System.out.println("jitter_buffer " + id.substring(0, Math.min(8, id.length())) + " ms=" + ms + " concealed=" + st.getMembers().get("concealedSamples"));
                    }
                });
            }

            Member(String id) {
                this.id = id;
                List<PeerConnection.IceServer> servers = iceServers(iceServers);
                PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(servers);
                config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
                // A call, not a stream: after a stall on a mobile network the default buffer (200 packets, 4 s) grew
                // and stayed, and both sides heard each other 2-3 s late (ANSWER_DELAY.md 8.7).
                config.audioJitterBufferMaxPackets = JITTER_BUFFER_PACKETS;
                config.audioJitterBufferFastAccelerate = true;
                pc = factory.createPeerConnection(config, new PeerConnection.Observer() {
                    // A relay candidate guarantees a path: answer without waiting for the rest of gathering.
                    public void onIceCandidate(IceCandidate c) { if (c.sdp.contains(" typ relay")) gathered.countDown(); }
                    public void onIceGatheringChange(PeerConnection.IceGatheringState s) { if (s == PeerConnection.IceGatheringState.COMPLETE) gathered.countDown(); }
                    public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                        ice = s.name();
                        // The first time a phone has a path: the app completes a transfer on it (MULTIPARTY_TRANSFER_V6.md).
                        if ((s == PeerConnection.IceConnectionState.CONNECTED || s == PeerConnection.IceConnectionState.COMPLETED) && !announced) {
                            announced = true;
                            try { send(new JSONObject().put("type", "member_connected").put("call_id", callId).put("member", id)); } catch (Exception ignored) {}
                        }
                    }
                    public void onTrack(RtpTransceiver t) {
                        MediaStreamTrack remote = t.getReceiver().track();
                        if (remote instanceof AudioTrack) ((AudioTrack) remote).addSink((data, bits, rate, channels, frames, ts) -> onRemoteAudio(Member.this, data, bits, rate, channels, frames));
                    }
                    public void onSignalingChange(PeerConnection.SignalingState s) {}
                    public void onIceConnectionReceivingChange(boolean b) {}
                    public void onIceCandidatesRemoved(IceCandidate[] c) {}
                    public void onAddStream(MediaStream s) {}
                    public void onRemoveStream(MediaStream s) {}
                    public void onDataChannel(DataChannel d) {}
                    public void onRenegotiationNeeded() {}
                });
                // The same downlink track on every phone's connection: WebRTC's capture feeds each sending stream itself.
                pc.addTrack(track, java.util.Collections.singletonList("call")); // not List.of: Android 11 and later only
                System.out.println("member " + id + " joined " + callId.substring(0, 8) + " servers=" + servers.size() + " members=" + (members.size() + 1));
            }

            void close() {
                PortMap.release(mappings);
                try { pc.close(); } catch (Exception ignored) {}
                try { pc.dispose(); } catch (Exception ignored) {}
            }
        }

        Session(String callId, JSONObject iceServers, JSONObject cellular) throws Exception {
            this.callId = callId;
            this.iceServers = iceServers;
            quiet = takeQuiet();
            List<NetworkChangeDetector.NetworkInformation> networks = activeNetworks(cellular);
            NetworkMonitor.getInstance().setNetworkChangeDetectorFactory((observer, ctx) -> new NetworkChangeDetector() {
                public NetworkChangeDetector.ConnectionType getCurrentConnectionType() { return NetworkChangeDetector.ConnectionType.CONNECTION_WIFI; }
                public boolean supportNetworkCallback() { return true; }
                public List<NetworkChangeDetector.NetworkInformation> getActiveNetworkList() { return networks; }
                public void destroy() {}
            });
            current = this;
            ensureFactory();
            MediaConstraints processing = new MediaConstraints();
            for (String key : new String[]{"googEchoCancellation", "googAutoGainControl", "googNoiseSuppression", "googHighpassFilter"})
                processing.mandatory.add(new MediaConstraints.KeyValuePair(key, "false"));
            source = factory.createAudioSource(processing);
            track = factory.createAudioTrack("downlink", source);
            mixer = new UplinkMixer(block -> {
                RecordTap t = tap;
                if (t != null) t.addNear(block, block.length, sinkRate);
                if (!uplink.offer(block)) { dropped.incrementAndGet(); droppedQueue.incrementAndGet(); }
            });
            new Thread(this::injectUplink, "uplink").start();
            new Thread(this::watch, "watch").start();
            System.out.println("session " + callId.substring(0, 8) + " started");
            synchronized (BridgeMain.class) {
                if (callId.equals(pendingRecord)) { pendingRecord = null; record(true); }
                if (pendingNotice != null) {
                    PendingNotice p = pendingNotice;
                    pendingNotice = null;
                    if (p.callId.equals(callId)) for (int i = 0; i < p.pieces.size(); i++) notice(p.pieces.get(i), i == p.pieces.size() - 1 ? p.end : null);
                }
            }
        }

        /**
         * Answers each newer offer from a member on its own connection (an ICE restart has a higher generation); older
         * ones are rejected. A new member joins on its first offer, unless the call already has MAX_MEMBERS phones.
         */
        void offer(String memberId, int gen, String sdp, JSONObject ice) throws Exception {
            if (ice != null) iceServers = ice;
            Member m;
            synchronized (this) {
                m = members.get(memberId);
                if (m == null) {
                    if (members.size() >= MAX_MEMBERS) {
                        send(new JSONObject().put("type", "reject").put("call_id", callId).put("member", memberId).put("gen", gen).put("reason", "full"));
                        return;
                    }
                    m = new Member(memberId);
                    members.put(memberId, m);
                    if (!listening.contains(memberId)) mixer.add(memberId);
                }
            }
            if (gen <= m.generation) {
                send(new JSONObject().put("type", "reject").put("call_id", callId).put("member", memberId).put("gen", gen).put("reason", "stale_generation"));
                return;
            }
            m.generation = gen;
            if (gen > 0) m.lastRestart = SystemClock.elapsedRealtime();
            m.gathered = new CountDownLatch(1);
            final Member member = m;
            // Each step timed: a Huawei gateway answered a phone on mobile data 2.9-4.5 s after the offer reached it,
            // with gathering itself 82 ms (ANSWER_DELAY.md 8.6).
            long t0 = SystemClock.elapsedRealtime();
            await(o -> member.pc.setRemoteDescription(o, new SessionDescription(SessionDescription.Type.OFFER, sdp)));
            long t1 = SystemClock.elapsedRealtime();
            SessionDescription answer = await(o -> member.pc.createAnswer(o, new MediaConstraints()));
            long t2 = SystemClock.elapsedRealtime();
            await(o -> member.pc.setLocalDescription(o, answer));
            // Candidates are not trickled, so wait for a relay candidate or the end of gathering, at most 1.5 s.
            long started = SystemClock.elapsedRealtime();
            m.gathered.await(1500, TimeUnit.MILLISECONDS);
            long t3 = SystemClock.elapsedRealtime();
            PortMap.release(m.mappings);
            String local = PortMap.augment(m.pc.getLocalDescription().description, m.mappings);
            long t4 = SystemClock.elapsedRealtime();
            System.out.println("answer " + memberId + " gen=" + gen + " gathered_ms=" + (t3 - started) + " relay=" + local.contains(" typ relay")
                + " remote_ms=" + (t1 - t0) + " create_ms=" + (t2 - t1) + " local_ms=" + (started - t2) + " ports_ms=" + (t4 - t3)
                + " since_received_ms=" + (offerReceivedAt > 0 ? t4 - offerReceivedAt : -1) + " at=" + clock());
            send(new JSONObject().put("type", "answer").put("call_id", callId).put("member", memberId).put("gen", gen).put("sdp", local));
        }

        /** A member leaves (it hung up, was removed, or lost its connection); the others carry on. */
        void leave(String memberId, String reason) {
            Member m;
            synchronized (this) {
                m = members.remove(memberId);
                if (m == null) return;
                mixer.remove(memberId);
            }
            m.close();
            System.out.println("member " + memberId + " left " + callId.substring(0, 8) + " " + reason + " members=" + members.size());
            listening.remove(memberId);
            try { send(new JSONObject().put("type", "member_ended").put("call_id", callId).put("member", memberId).put("reason", reason)); } catch (Exception ignored) {}
            // The assistant keeps the call's media going on its own (SCREENING_ASSISTANT.md 10.3).
            if (members.isEmpty() && assistant == null) stopSession(reason);
        }

        /** The ICE state the app logs: the only member's, or each member's when there are several. */
        private String iceSummary() {
            if (members.size() == 1) return members.values().iterator().next().ice;
            StringBuilder b = new StringBuilder();
            for (Member m : members.values()) b.append(b.length() == 0 ? "" : ",").append(m.id).append(':').append(m.ice);
            return b.length() == 0 ? "NONE" : b.toString();
        }

        private void watch() {
            int tick = 0;
            while (stop.isEmpty()) {
                // Every 200 ms, as QuietHold before the session: at 500 ms Huawei's earpiece stayed up to 0.54 s at
                // the call volume it reset as the call went off-hook (ANSWER_DELAY.md 8.6).
                try { Thread.sleep(200); } catch (InterruptedException e) { return; }
                long now = SystemClock.elapsedRealtime();
                for (Member m : members.values()) {
                    if (m.ice.equals("CONNECTED") || m.ice.equals("COMPLETED")) m.lastConnected = now;
                    else if (m.lastConnected > 0 && now - m.lastConnected > (m.lastRestart > 0 && now - m.lastRestart < RESTART_RECENT_MS ? RECOVERY_ASKING_MS : RECOVERY_MS)) leave(m.id, "ice_lost");
                }
                quiet.enforce();
                if (++tick % 15 == 0) {
                    for (Member m : members.values()) m.logJitterBuffer();
                    try {
                        android.media.AudioTrack tx = txTrack;
                        String txStats = tx == null ? "none" : "rate=" + tx.getSampleRate() + " head=" + (tx.getPlaybackHeadPosition() & 0xffffffffL)
                            + " written=" + written + " underruns=" + tx.getUnderrunCount() + " ratio=" + String.format(java.util.Locale.ROOT, "%.3f", txRatio);
                        String ice = iceSummary();
                        System.out.println("state ice=" + ice + " members=" + members.size() + " uplink=" + sinkFrames.get() + "/" + sinkNonzero.get() + "/" + sinkPeak.get() + "@" + sinkRate
                            + " injected=" + injected.get() + " dropped_queue=" + droppedQueue.get() + " dropped_partial=" + droppedPartial.get() + " tx " + txStats
                            + " queue=" + uplink.size() + " clipped=" + sinkClipped.get() + "/" + sinkSamples.get()
                            + " threads=" + Thread.activeCount());
                        send(new JSONObject().put("type", "state").put("call_id", callId).put("ice", ice).put("members", members.size())
                            .put("downlink", capFrames.get() + "/" + capNonzero.get() + "/" + capPeak.get())
                            .put("uplink", sinkFrames.get() + "/" + sinkNonzero.get() + "/" + sinkPeak.get())
                            .put("injected", injected.get()).put("dropped", dropped.get()).put("route", route).put("silence", quiet.status()));
                    } catch (Exception ignored) {}
                }
            }
        }

        /** Called on WebRTC's playout thread with each phone's decoded 10 ms block; downmixes and adds it to the uplink sum. */
        private void onRemoteAudio(Member m, ByteBuffer data, int bits, int rate, int channels, int frames) {
            count(data, sinkFrames, sinkNonzero, sinkPeak);
            if (bits != 16) return;
            sinkRate = rate;
            ByteBuffer pcm = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            short[] mono = new short[frames];
            int clipped = 0;
            for (int i = 0; i < frames; i++) {
                int sum = 0;
                for (int c = 0; c < channels; c++) sum += pcm.getShort();
                mono[i] = (short) (sum / channels);
                if (Math.abs((int) mono[i]) >= 32000) clipped++;
            }
            sinkClipped.addAndGet(clipped);
            sinkSamples.addAndGet(frames);
            mixer.mix(m.id, mono);
        }

        /** How long the far-end recording runs before the uplink track is opened, where the order matters. */
        private static final long RECORD_LEAD_MS = 500;

        /** How long sound may be written while the route is not Telephony TX, on phones that only show it under sound. */
        private static final long LOUD_OFF_ROUTE_MS = 3000;

        private static boolean loud(short[] block) {
            for (short v : block) if (v > 300 || v < -300) return true;
            return false;
        }

        /** Writes the phone's audio to Telephony TX, and stops on any other route so it can never play on the speaker. */
        private void injectUplink() {
            android.media.AudioTrack track = null;
            UplinkPacer pacer = null;
            long unknownSince = 0, lastReport = 0, loudOffRouteSince = 0, lastAudio = 0, fillFrom = 0, filled = 0, openedFor = 0;
            try {
                while (stop.isEmpty()) {
                    // A short wait, so audio held back by a full TX buffer is written as soon as there is room.
                    short[] block = uplink.poll(10, TimeUnit.MILLISECONDS);
                    if (block == null && pacer == null) continue;
                    // With nothing to send (every phone is only listening in) the track would run dry. A track that
                    // has run dry misleads the pacer about how fast it takes audio: on the Redmi the phone taking the
                    // call over was then played a third too fast, and so too high, and on a Mate 40 Pro+ the track
                    // did not get back to Telephony TX and was not heard at all (both 2026-11-10). Silence keeps it
                    // going; not at once, so that a phone's audio arriving a little late is not pushed back by
                    // silence put in ahead of it. As much of it as time has passed, so the track is fed at its own
                    // pace and not a little slower.
                    long nowFill = SystemClock.elapsedRealtime();
                    if (block != null) { lastAudio = nowFill; fillFrom = 0; }
                    else if (track != null && nowFill - lastAudio > 60) {
                        if (fillFrom == 0) { fillFrom = nowFill - 10; filled = 0; }
                        long owed = (nowFill - fillFrom) * sinkRate / 1000 - filled;
                        if (owed > 0) { block = new short[(int) Math.min(owed, sinkRate / 10)]; filled += block.length; }
                    }
                    if (track != null && HISILICON_UPLINK && recordingSince != openedFor) {
                        // The recording stopped and started again under an open uplink: the assistant left and the
                        // phone taking the call over came in after it, with no phone listening in between. A recording
                        // started that way reads zeros, and the phone heard nothing of the caller (Mate 40 Pro+,
                        // 2026-11-11: packets arrived, all silent). The uplink is closed and opened again behind it.
                        try { track.stop(); } catch (Exception ignored) {}
                        track.release();
                        track = null;
                        pacer = null;
                        txTrack = null;
                        fillFrom = 0;
                        System.out.println("tx closed: the far-end recording restarted");
                    }
                    if (track == null && HISILICON_UPLINK) {
                        // The far-end recording must be running first, or it reads zeros (see HISILICON_UPLINK).
                        long since = recordingSince;
                        if (since == 0 || SystemClock.elapsedRealtime() - since < RECORD_LEAD_MS) continue;
                        openedFor = since;
                    }
                    if (track == null) {
                        track = telephonyTrack(sinkRate);
                        track.play();
                        txTrack = track;
                        final android.media.AudioTrack t = track;
                        pacer = new UplinkPacer(track.getSampleRate(), new UplinkPacer.Sink() {
                            public int write(short[] a, int off, int len) { return t.write(a, off, len, android.media.AudioTrack.WRITE_NON_BLOCKING); }
                            public long head() { return t.getPlaybackHeadPosition() & 0xffffffffL; }
                        });
                        System.out.println("tx track rate=" + track.getSampleRate() + " sink_rate=" + sinkRate + " block=" + (block == null ? 0 : block.length)
                            + " buffer_frames=" + track.getBufferSizeInFrames() + " capacity=" + track.getBufferCapacityInFrames());
                    }
                    AudioDeviceInfo routed = track.getRoutedDevice();
                    route = routed == null ? -1 : routed.getType();
                    if (route != AudioDeviceInfo.TYPE_TELEPHONY && HISILICON_UPLINK
                            && (route == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE || route == -1)) {
                        // The earpiece is what this phone reports while the track is quiet; it is at volume 0. Sound
                        // moves the route to Telephony TX within a few hundred milliseconds (210 and 330 ms measured),
                        // so sound that stays off it for seconds means the uplink is not being reached.
                        long t = SystemClock.elapsedRealtime();
                        if (block == null || !loud(block)) loudOffRouteSince = 0;
                        else if (loudOffRouteSince == 0) loudOffRouteSince = t;
                        else if (t - loudOffRouteSince > LOUD_OFF_ROUTE_MS) { stopSession("route_not_telephony_" + route); break; }
                    } else if (route != AudioDeviceInfo.TYPE_TELEPHONY) {
                        if (route != -1) { stopSession("route_not_telephony_" + route); break; }
                        if (unknownSince == 0) unknownSince = SystemClock.elapsedRealtime();
                        if (SystemClock.elapsedRealtime() - unknownSince > 2000) { stopSession("route_unknown"); break; }
                        continue;
                    } else {
                        loudOffRouteSince = 0;
                    }
                    unknownSince = 0;
                    long now = SystemClock.elapsedRealtime();
                    if (block != null) {
                        long lostBefore = pacer.droppedSamples;
                        pacer.offer(block, now);
                        if (pacer.droppedSamples > lostBefore) { dropped.incrementAndGet(); droppedPartial.incrementAndGet(); }
                        else injected.incrementAndGet();
                    } else {
                        pacer.flush();
                    }
                    written = pacer.written;
                    txRatio = pacer.ratio;
                    if (now - lastReport >= 5000) {
                        lastReport = now;
                        System.out.println("tx pacer consumed=" + Math.round(pacer.rate) + "/s ratio=" + String.format(java.util.Locale.ROOT, "%.3f", pacer.ratio)
                            + " backlog=" + pacer.backlog() + " held=" + pacer.pendingLen + " dropped_samples=" + pacer.droppedSamples);
                    }
                }
            } catch (Exception e) {
                stopSession("inject_" + e.getClass().getSimpleName());
            } finally {
                if (track != null) { try { track.stop(); } catch (Exception ignored) {} track.release(); }
            }
        }

        void close(String reason) {
            stop = reason;
            if (tap != null) {
                tap = null;
                try { send(new JSONObject().put("type", "pcm_end").put("call_id", callId).put("reason", "media_" + reason)); } catch (Exception ignored) {}
            }
            if (current == this) current = null;
            Assistant a = assistant;
            assistant = null;
            if (a != null) a.close();
            for (Member m : members.values()) m.close();
            members.clear();
            try { track.dispose(); } catch (Exception ignored) {}
            try { source.dispose(); } catch (Exception ignored) {}
            quiet.disable();
            System.out.println("session " + callId.substring(0, 8) + " ended " + reason + " injected=" + injected.get() + " dropped=" + dropped.get()
                + " downlink=" + capFrames.get() + "/" + capNonzero.get() + "/" + capPeak.get() + " uplink=" + sinkFrames.get() + "/" + sinkNonzero.get() + "/" + sinkPeak.get() + " clipped=" + sinkClipped.get() + "/" + sinkSamples.get());
            try { send(new JSONObject().put("type", "ended").put("call_id", callId).put("reason", reason)); } catch (Exception ignored) {}
            warmTx();
        }
    }

    /**
     * A data-only peer connection for the phones' self-test (SELF_TEST.md): no audio track, so nothing is recorded or
     * played; it echoes each message back so the phone can time the path, and closes after 20 s or when the phone goes.
     */
    static final class Probe {
        private static volatile Probe open;
        private final PeerConnection pc;
        private final CountDownLatch gathered = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final List<Integer> mappings = new ArrayList<>();

        /** A recording's download (RECORDING_V7.md 5): the probe ID, which the app's chunks come under; null for a self-test. */
        private final String file;
        private volatile boolean pumping;
        /** The app's chunks of the file being downloaded, by probe ID. */
        static final java.util.Map<String, ArrayBlockingQueue<JSONObject>> chunks = new java.util.concurrent.ConcurrentHashMap<>();

        private Probe(JSONObject iceServers, JSONObject cellular, String file) throws Exception {
            this.file = file;
            List<NetworkChangeDetector.NetworkInformation> networks = activeNetworks(cellular);
            NetworkMonitor.getInstance().setNetworkChangeDetectorFactory((observer, ctx) -> new NetworkChangeDetector() {
                public NetworkChangeDetector.ConnectionType getCurrentConnectionType() { return NetworkChangeDetector.ConnectionType.CONNECTION_WIFI; }
                public boolean supportNetworkCallback() { return true; }
                public List<NetworkChangeDetector.NetworkInformation> getActiveNetworkList() { return networks; }
                public void destroy() {}
            });
            ensureFactory();
            List<PeerConnection.IceServer> servers = iceServers(iceServers);
            PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(servers);
            config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            pc = factory.createPeerConnection(config, new PeerConnection.Observer() {
                public void onIceCandidate(IceCandidate c) { if (c.sdp.contains(" typ relay")) gathered.countDown(); }
                public void onIceGatheringChange(PeerConnection.IceGatheringState s) { if (s == PeerConnection.IceGatheringState.COMPLETE) gathered.countDown(); }
                public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                    if (s == PeerConnection.IceConnectionState.FAILED || s == PeerConnection.IceConnectionState.CLOSED) finished.countDown();
                }
                public void onDataChannel(DataChannel d) {
                    if (Probe.this.file != null) { serveFile(d); return; }
                    d.registerObserver(new DataChannel.Observer() {
                        public void onBufferedAmountChange(long l) {}
                        public void onStateChange() { if (d.state() == DataChannel.State.CLOSED) finished.countDown(); }
                        public void onMessage(DataChannel.Buffer b) {
                            ByteBuffer copy = ByteBuffer.allocateDirect(b.data.remaining());
                            copy.put(b.data);
                            copy.flip();
                            d.send(new DataChannel.Buffer(copy, b.binary));
                        }
                    });
                }
                public void onTrack(RtpTransceiver t) {}
                public void onSignalingChange(PeerConnection.SignalingState s) {}
                public void onIceConnectionReceivingChange(boolean b) {}
                public void onIceCandidatesRemoved(IceCandidate[] c) {}
                public void onAddStream(MediaStream s) {}
                public void onRemoveStream(MediaStream s) {}
                public void onRenegotiationNeeded() {}
            });
        }

        /**
         * Sends the file the app hands over, chunk by chunk, on the phone's channel: the phone's first message says
         * from which byte ({"offset": n}, to carry on after a break); binary pieces follow, then {"done": true, "size": n}.
         * The app is asked for each next chunk only once the channel has room, so a slow relay holds nothing up here.
         */
        private void serveFile(DataChannel d) {
            d.registerObserver(new DataChannel.Observer() {
                public void onBufferedAmountChange(long l) {}
                public void onStateChange() { if (d.state() == DataChannel.State.CLOSED) finished.countDown(); }
                public void onMessage(DataChannel.Buffer b) {
                    if (b.binary || pumping) return;
                    byte[] raw = new byte[b.data.remaining()];
                    b.data.get(raw);
                    long offset;
                    try { offset = new JSONObject(new String(raw, "UTF-8")).optLong("offset"); } catch (Exception e) { return; }
                    pumping = true;
                    new Thread(() -> pump(d, offset), "file").start();
                }
            });
        }

        private void pump(DataChannel d, long offset) {
            ArrayBlockingQueue<JSONObject> q = new ArrayBlockingQueue<>(2);
            chunks.put(file, q);
            long at = offset, sent = 0;
            try {
                while (true) {
                    send(new JSONObject().put("type", "file_need").put("probe_id", file).put("offset", at));
                    JSONObject c = q.poll(30, TimeUnit.SECONDS);
                    if (c == null || c.has("error") || c.optLong("offset") != at) {
                        String why = c == null ? "timeout" : c.optString("error", "offset");
                        d.send(new DataChannel.Buffer(ByteBuffer.wrap(new JSONObject().put("error", why).toString().getBytes("UTF-8")), false));
                        System.out.println("file " + file.substring(0, 8) + " stopped " + why);
                        return;
                    }
                    byte[] data = android.util.Base64.decode(c.optString("data"), android.util.Base64.NO_WRAP);
                    for (int i = 0; i < data.length; i += 16 * 1024) {
                        int n = Math.min(16 * 1024, data.length - i);
                        while (d.bufferedAmount() > 512 * 1024) {
                            if (d.state() != DataChannel.State.OPEN) return;
                            Thread.sleep(20);
                        }
                        ByteBuffer piece = ByteBuffer.allocateDirect(n);
                        piece.put(data, i, n);
                        piece.flip();
                        if (!d.send(new DataChannel.Buffer(piece, true))) return;
                    }
                    at += data.length;
                    sent += data.length;
                    if (c.optBoolean("last")) {
                        d.send(new DataChannel.Buffer(ByteBuffer.wrap(new JSONObject().put("done", true).put("size", at).toString().getBytes("UTF-8")), false));
                        System.out.println("file " + file.substring(0, 8) + " sent " + sent + " bytes from " + offset);
                        return;
                    }
                }
            } catch (Exception e) {
                System.out.println("file " + file.substring(0, 8) + " " + e);
            } finally {
                chunks.remove(file);
            }
        }

        static void run(String id, String offer, JSONObject ice, JSONObject cellular, boolean file) {
            Probe p = null;
            try {
                Probe previous = open;
                if (previous != null) previous.finished.countDown();
                p = new Probe(ice, cellular, file ? id : null);
                open = p;
                final Probe q = p;
                await(o -> q.pc.setRemoteDescription(o, new SessionDescription(SessionDescription.Type.OFFER, offer)));
                SessionDescription answer = await(o -> q.pc.createAnswer(o, new MediaConstraints()));
                await(o -> q.pc.setLocalDescription(o, answer));
                p.gathered.await(1500, TimeUnit.MILLISECONDS);
                send(new JSONObject().put("type", "probe_answer").put("probe_id", id).put("sdp", PortMap.augment(p.pc.getLocalDescription().description, p.mappings)));
                System.out.println("probe " + id.substring(0, 8) + " answered");
                // A call wanting the media closes the test at once; a download may take its time on a slow relay.
                long end = SystemClock.elapsedRealtime() + (file ? 30 * 60_000 : 20_000);
                while (SystemClock.elapsedRealtime() < end && session == null && !p.finished.await(200, TimeUnit.MILLISECONDS)) {}
            } catch (Exception e) {
                System.out.println("probe failed " + e);
                try { send(new JSONObject().put("type", "probe_failed").put("probe_id", id).put("reason", "failed")); } catch (Exception ignored) {}
            } finally {
                if (p != null) {
                    PortMap.release(p.mappings);
                    if (open == p) open = null;
                    try { p.pc.close(); } catch (Exception ignored) {}
                    try { p.pc.dispose(); } catch (Exception ignored) {}
                    System.out.println("probe " + id.substring(0, Math.min(8, id.length())) + " closed");
                }
            }
        }
    }

    private static void count(ByteBuffer data, AtomicLong frames, AtomicLong nonzero, AtomicLong peak) {
        ByteBuffer pcm = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int max = 0;
        while (pcm.remaining() >= 2) max = Math.max(max, Math.abs((int) pcm.getShort()));
        frames.incrementAndGet();
        if (max > 0) nonzero.incrementAndGet();
        peak.accumulateAndGet(max, Math::max);
    }

    /** The session whose counters the shared audio module's capture callback feeds. */
    private static volatile Session current;

    /** When the far-end recording started (elapsed realtime), 0 while it is not running. */
    private static volatile long recordingSince;

    private static synchronized void ensureFactory() {
        if (factory != null) return;
        JavaAudioDeviceModule.Builder builder = JavaAudioDeviceModule.builder(context);
        if (HISILICON_UPLINK) {
            builder.setAudioSource(MediaRecorder.AudioSource.VOICE_CALL).setInputSampleRate(16000).setUseStereoInput(true)
                .setAudioTrackStateCallback(new JavaAudioDeviceModule.AudioTrackStateCallback() {
                    @Override public void onWebRtcAudioTrackStart() { PacedSink.install(); }
                    @Override public void onWebRtcAudioTrackStop() {}
                })
                .setAudioRecordStateCallback(new JavaAudioDeviceModule.AudioRecordStateCallback() {
                    @Override public void onWebRtcAudioRecordStart() { recordingSince = SystemClock.elapsedRealtime(); System.out.println("far-end recording started"); }
                    @Override public void onWebRtcAudioRecordStop() { recordingSince = 0; System.out.println("far-end recording stopped"); }
                });
        } else {
            builder.setAudioSource(MediaRecorder.AudioSource.VOICE_DOWNLINK).setInputSampleRate(8000);
        }
        adm = builder
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setSamplesReadyCallback(s -> { Session c = current; if (c != null) c.onCaptured(s); })
            .createAudioDeviceModule();
        // The phone's audio goes to the call uplink only, never to this phone's earpiece or speaker.
        adm.setSpeakerMute(true);
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory();
    }

    /**
     * Stands in for the track the call library plays received audio on, where no second track may play (see
     * HISILICON_UPLINK). The library's playout thread writes each 10 ms buffer here and waits as it would for a real
     * track, which keeps it pulling received audio at the right pace; nothing is played. The received audio itself
     * reaches the uplink through the sinks on the remote tracks, as on every phone.
     */
    static final class PacedSink extends android.media.AudioTrack {
        private final int bytesPerSecond;
        private long next;

        private PacedSink(android.media.AudioTrack like) {
            super(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                like.getFormat(), like.getBufferSizeInFrames() * like.getChannelCount() * 2, MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            bytesPerSecond = like.getSampleRate() * like.getChannelCount() * 2;
        }

        @Override public void play() {}
        @Override public void stop() {}
        @Override public void pause() {}
        @Override public int getPlayState() { return PLAYSTATE_PLAYING; }

        @Override public int write(ByteBuffer data, int size, int mode) {
            long now = System.nanoTime();
            // Far behind (the process was paused): start again from now rather than run the buffers through in a burst.
            if (next == 0 || now - next > 200_000_000L) next = now;
            next += size * 1_000_000_000L / bytesPerSecond;
            long wait = next - now;
            if (wait > 0) try { Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return size;
        }

        /** Swaps the library's playout track for one of these, once that track has started. */
        static void install() {
            try {
                Object output = adm.getClass().getField("audioOutput").get(adm);
                Field field = output.getClass().getDeclaredField("audioTrack");
                field.setAccessible(true);
                android.media.AudioTrack real = (android.media.AudioTrack) field.get(output);
                if (real == null || real instanceof PacedSink) return;
                field.set(output, new PacedSink(real));
                // The playout thread may be inside a write to the real track; pausing makes that write return, and the
                // next one goes to the stand-in. Released a moment later, when nothing can still be using it.
                real.pause();
                new Thread(() -> {
                    try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                    try { real.flush(); real.release(); } catch (Exception e) { System.out.println("playout track release failed " + e); }
                }, "playout-release").start();
                System.out.println("playout track replaced");
            } catch (Throwable t) {
                System.out.println("playout track not replaced: " + t);
            }
        }
    }

    private static AudioDeviceInfo telephonyTx() throws Exception {
        // No usable Context under app_process, so AudioManager#getDevices is out of reach.
        AudioDeviceInfo[] devices = (AudioDeviceInfo[]) AudioManager.class.getMethod("getDevicesStatic", int.class).invoke(null, AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo d : devices) if (d.isSink() && d.getType() == AudioDeviceInfo.TYPE_TELEPHONY) return d;
        throw new IllegalStateException("TX_ROUTE_UNAVAILABLE");
    }

    private static android.media.AudioTrack telephonyTrack(int rate) throws Exception {
        AudioDeviceInfo tx = telephonyTx();
        int min = android.media.AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        // The smallest track bounds the backlog: a burst that fills it is dropped instead of delaying the rest of the call.
        android.media.AudioTrack track = new android.media.AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(min).setTransferMode(android.media.AudioTrack.MODE_STREAM)
            .build();
        if (!track.setPreferredDevice(tx)) throw new IllegalStateException("TX_ROUTE_REJECTED");
        return track;
    }

    /**
     * Earpiece and speaker at volume 0 and the microphone muted, re-asserted until the end (QuietHold before the
     * media, the session's watch after), restored at the end. Each time the volume is found raised again, the log
     * says so, with the phone's audio mode: the Mate 40 Pro+ was heard in its earpiece before the media (2026-11-13).
     */
    static final class Silence implements QuietHold.Silenced {
        private static final int STREAM_VOICE_CALL = 0, DEVICE_EARPIECE = 1, DEVICE_SPEAKER = 2, PUBLIC_MIN = 1;
        private final int normal, max;
        private final long since = SystemClock.elapsedRealtime();
        private int micReasserts, volumeReasserts;
        private boolean restored;
        private String lastSeen = "";
        private Silence(int normal, int max) { this.normal = normal; this.max = max; }

        static Silence enable() throws Exception {
            java.lang.Process p = new ProcessBuilder("cmd", "media_session", "volume", "--stream", "0", "--get").redirectErrorStream(true).start();
            // InputStream.readAllBytes() only exists from Android 13; a Huawei on Android 12 crashed here (2026-11-10).
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            try (java.io.InputStream in = p.getInputStream()) {
                for (int n; (n = in.read(chunk)) > 0; ) out.write(chunk, 0, n);
            }
            String text = out.toString("UTF-8");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("volume is (\\d+) in range \\[\\d+\\.\\.(\\d+)\\]").matcher(text);
            if (!m.find()) throw new IllegalStateException("VOLUME_UNREADABLE");
            Silence s = new Silence(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            call("initStreamVolume", new Class<?>[]{int.class, int.class, int.class}, STREAM_VOICE_CALL, 0, s.max);
            s.enforce();
            s.micReasserts = 0;
            s.volumeReasserts = 0;
            System.out.println("quiet on normal=" + s.normal + " max=" + s.max);
            return s;
        }

        public synchronized void enforce() {
            if (restored) return;
            try {
                // What the phone has now, before it is set again: a change shows here once (the mode with it).
                int earpiece = volume(DEVICE_EARPIECE), speaker = volume(DEVICE_SPEAKER);
                String seen = "earpiece=" + earpiece + " speaker=" + speaker + " mode=" + mode() + " mic_muted=" + micMuted();
                if (!seen.equals(lastSeen)) {
                    System.out.println("quiet +" + (SystemClock.elapsedRealtime() - since) + "ms " + seen);
                    lastSeen = seen;
                }
                if (earpiece > 0 || speaker > 0) volumeReasserts++;
                call("setStreamVolumeIndexAS", new Class<?>[]{int.class, int.class, int.class}, STREAM_VOICE_CALL, 0, DEVICE_EARPIECE);
                call("setStreamVolumeIndexAS", new Class<?>[]{int.class, int.class, int.class}, STREAM_VOICE_CALL, 0, DEVICE_SPEAKER);
                if (!micMuted()) { micReasserts++; call("muteMicrophone", new Class<?>[]{boolean.class}, true); }
            } catch (Exception e) { System.out.println("silence enforce failed " + e); }
        }

        String status() {
            try { return "mic_muted=" + micMuted() + " mic_reasserts=" + micReasserts + " volume_reasserts=" + volumeReasserts; } catch (Exception e) { return "mic_state=unknown"; }
        }

        public synchronized void disable() {
            if (restored) return;
            restored = true;
            System.out.println("quiet off after " + (SystemClock.elapsedRealtime() - since) + "ms volume_reasserts=" + volumeReasserts + " mic_reasserts=" + micReasserts);
            try {
                call("initStreamVolume", new Class<?>[]{int.class, int.class, int.class}, STREAM_VOICE_CALL, PUBLIC_MIN, max);
                call("setStreamVolumeIndexAS", new Class<?>[]{int.class, int.class, int.class}, STREAM_VOICE_CALL, normal, DEVICE_EARPIECE);
                call("setStreamVolumeIndexAS", new Class<?>[]{int.class, int.class, int.class}, STREAM_VOICE_CALL, normal, DEVICE_SPEAKER);
                call("muteMicrophone", new Class<?>[]{boolean.class}, false);
                new ProcessBuilder("cmd", "media_session", "volume", "--stream", "0", "--set", String.valueOf(normal)).start().waitFor();
            } catch (Exception e) { System.out.println("silence restore failed " + e); }
        }

        /** The call volume the audio policy applies on that output now (-1 if unreadable). */
        private static int volume(int device) {
            try {
                return (int) Class.forName("android.media.AudioSystem").getMethod("getStreamVolumeIndex", int.class, int.class).invoke(null, STREAM_VOICE_CALL, device);
            } catch (Exception e) { return -1; }
        }

        /** The phone's audio mode: 2 in a call, 0 normal, 1 ringing (-1 if unreadable). */
        private static int mode() {
            try { return ((AudioManager) context.getSystemService(Context.AUDIO_SERVICE)).getMode(); } catch (Exception e) { return -1; }
        }

        private static boolean micMuted() throws Exception {
            return (boolean) Class.forName("android.media.AudioSystem").getMethod("isMicrophoneMuted").invoke(null);
        }

        private static void call(String name, Class<?>[] types, Object... values) throws Exception {
            int rc = (int) Class.forName("android.media.AudioSystem").getMethod(name, types).invoke(null, values);
            if (rc != 0) throw new IllegalStateException(name + "_REJECTED_" + rc);
        }
    }

    /** ConnectivityManager hangs in app_process, so read the interfaces directly; handle 0 leaves sockets unbound. */
    /**
     * Wi-Fi, unbound (handle 0, the default network), and, when the app named it, the phone's cellular data with its
     * real network handle so WebRTC binds those sockets to it (CELLULAR_DIRECT_RESEARCH.md). Of cellular only the
     * public IPv6 addresses: its IPv4 sits behind the carrier's shared NAT and would only add relay allocations.
     */
    private static List<NetworkChangeDetector.NetworkInformation> activeNetworks(JSONObject cellular) throws Exception {
        List<NetworkChangeDetector.NetworkInformation> out = activeNetworks();
        if (cellular == null) return out;
        String name = cellular.optString("iface");
        long handle = cellular.optLong("handle");
        java.net.NetworkInterface nif = name.isEmpty() ? null : java.net.NetworkInterface.getByName(name);
        if (nif == null || handle == 0) return out;
        List<NetworkChangeDetector.IPAddress> ips = new ArrayList<>();
        for (java.net.InetAddress address : java.util.Collections.list(nif.getInetAddresses()))
            if (isPublicV6(address.getAddress())) ips.add(new NetworkChangeDetector.IPAddress(address.getAddress()));
        if (ips.isEmpty()) {
            System.out.println("cellular " + name + " has no public IPv6");
            return out;
        }
        // The phone's own data is the stand-by (DIRECT_PATHS.md 3.2): always offered, below everything on the Wi-Fi.
        // WebRTC picks a pair by the two sides' network cost first, then by priority. A cellular type made the relay
        // (Wi-Fi on this side) win over a working direct IPv6 pair, so the cost must be Wi-Fi's; typed as a VPN over
        // Wi-Fi it costs the same but sorts after the real Wi-Fi, so its addresses get a lower priority: a direct path
        // over the Wi-Fi (IPv6, a router port) goes first when it works, this one when only it does, and the relay
        // only when neither. Using this data at all is the owner's choice, with the switch on the gateway screen.
        out.add(new NetworkChangeDetector.NetworkInformation(name, NetworkChangeDetector.ConnectionType.CONNECTION_VPN,
            NetworkChangeDetector.ConnectionType.CONNECTION_WIFI, handle, ips.toArray(new NetworkChangeDetector.IPAddress[0])));
        System.out.println("cellular " + name + " offered as stand-by with " + ips.size() + " IPv6 address(es)");
        return out;
    }

    /** A global unicast IPv6 address (2000::/3): not link-local, unique-local (fc00::/7), loopback or IPv4. */
    static boolean isPublicV6(byte[] a) {
        return a.length == 16 && (a[0] & 0xE0) == 0x20;
    }

    private static List<NetworkChangeDetector.NetworkInformation> activeNetworks() throws Exception {
        List<NetworkChangeDetector.NetworkInformation> out = new ArrayList<>();
        for (java.net.NetworkInterface nif : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
            if (!nif.isUp() || nif.isLoopback()) continue;
            List<NetworkChangeDetector.IPAddress> ips = new ArrayList<>();
            for (java.net.InetAddress address : java.util.Collections.list(nif.getInetAddresses()))
                if (!address.isLinkLocalAddress()) ips.add(new NetworkChangeDetector.IPAddress(address.getAddress()));
            // WebRTC keys networks by handle, and every handle here is 0, so only one network survives: prefer Wi-Fi.
            if (ips.isEmpty() || !nif.getName().startsWith("wlan")) continue;
            out.add(new NetworkChangeDetector.NetworkInformation(nif.getName(), NetworkChangeDetector.ConnectionType.CONNECTION_WIFI,
                NetworkChangeDetector.ConnectionType.CONNECTION_NONE, 0, ips.toArray(new NetworkChangeDetector.IPAddress[0])));
        }
        return out;
    }

    private interface SdpCall { void run(SdpObserver o); }

    private static SessionDescription await(SdpCall call) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        SessionDescription[] result = new SessionDescription[1];
        String[] error = new String[1];
        call.run(new SdpObserver() {
            public void onCreateSuccess(SessionDescription d) { result[0] = d; done.countDown(); }
            public void onSetSuccess() { done.countDown(); }
            public void onCreateFailure(String e) { error[0] = e; done.countDown(); }
            public void onSetFailure(String e) { error[0] = e; done.countDown(); }
        });
        if (!done.await(5, TimeUnit.SECONDS) || error[0] != null) throw new IllegalStateException("SDP failed: " + error[0]);
        return result[0];
    }

    // Shell identity, adapted from scrcpy's Workarounds and FakeContext (Apache-2.0).
    private static Context installShellIdentity() throws Exception {
        Class<?> cls = Class.forName("android.app.ActivityThread");
        Constructor<?> ctor = cls.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object thread = ctor.newInstance();
        setField(cls, null, "sCurrentActivityThread", thread);
        Field system = cls.getDeclaredField("mSystemThread");
        system.setAccessible(true);
        system.setBoolean(thread, true);
        setField(cls, thread, "mInstrumentation", new Instrumentation());
        Class<?> bindCls = Class.forName("android.app.ActivityThread$AppBindData");
        Constructor<?> bindCtor = bindCls.getDeclaredConstructor();
        bindCtor.setAccessible(true);
        Object bind = bindCtor.newInstance();
        ApplicationInfo info = new ApplicationInfo();
        info.packageName = SHELL;
        setField(bindCls, bind, "appInfo", info);
        setField(cls, thread, "mBoundApplication", bind);
        Method getSystem = cls.getDeclaredMethod("getSystemContext");
        Context shell = new ShellContext((Context) getSystem.invoke(thread));
        setField(cls, thread, "mInitialApplication", Instrumentation.newApplication(Application.class, shell));
        return shell;
    }

    private static void setField(Class<?> cls, Object target, String name, Object value) throws Exception {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static final class ShellContext extends ContextWrapper {
        ShellContext(Context base) { super(base); }
        @Override public String getPackageName() { return SHELL; }
        @Override public String getOpPackageName() { return SHELL; }
        @Override public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(android.os.Process.SHELL_UID).setPackageName(SHELL).build();
        }
        @Override public Context getApplicationContext() { return this; }
    }

    /**
     * Linear-interpolation resampler for 16-bit mono with a ratio that may change between blocks (output samples per
     * input sample). Keeps its phase and last sample across blocks, so a stream of 10 ms blocks stays continuous.
     */
    static final class Resampler {
        private double pos;
        private short prev;
        private boolean primed;

        short[] process(short[] in, double ratio) {
            if (in.length == 0) return in;
            if (Math.abs(ratio - 1) < 1e-4 && pos == 0) { prev = in[in.length - 1]; primed = true; return in; }
            double step = 1 / ratio;
            short[] out = new short[(int) Math.ceil((in.length + 1) * ratio) + 2];
            int n = 0;
            // pos runs over [-1, in.length): -1 is the previous block's last sample.
            if (!primed) { prev = in[0]; primed = true; }
            while (pos < in.length - 1 + 1e-9) {
                int i = (int) Math.floor(pos);
                double f = pos - i;
                double a = i < 0 ? prev : in[i];
                double b = in[Math.min(i + 1, in.length - 1)];
                if (n < out.length) out[n++] = (short) Math.round(a + (b - a) * f);
                pos += step;
            }
            pos -= in.length;
            prev = in[in.length - 1];
            return java.util.Arrays.copyOf(out, n);
        }
    }

    /**
     * Sums the phones' audio for the call uplink (MULTIPARTY_TRANSFER_V6.md, 5a2). WebRTC hands each remote track's
     * 10 ms block to its sink on the playout thread, every phone once per tick; a tick's blocks are added up and the sum
     * goes out when every phone has given one, or when a phone gives a second before the others (a stalled phone does not
     * hold the rest back). With one phone every block goes out as it comes, as before.
     */
    static final class UplinkMixer {
        interface Out { void emit(short[] block); }
        private final Out out;
        private final java.util.Set<String> members = new java.util.HashSet<>(), given = new java.util.HashSet<>();
        private int[] acc;
        /** While set, every other member is turned down to a quarter, so this one is heard over them. */
        private String solo;
        /**
         * A member with no beat of its own (the recording notice): its blocks wait here and one goes out with each
         * tick of the others, so the uplink gets one block a tick whatever the two clocks do. Fed on its own timer it
         * made a tick go out early whenever its timer ran ahead of the phone's, a block more than the uplink takes,
         * which was dropped: about thirty times a second, heard by the far party as a loud buzz (2026-11-12).
         */
        private String rider;
        private final java.util.ArrayDeque<short[]> riding = new java.util.ArrayDeque<>();
        /** When a member with a beat last gave a block, by [clock] (ms): one that has stopped is not waited for. */
        private long lastBeat = Long.MIN_VALUE / 2;
        private final java.util.function.LongSupplier clock;
        /** The others are taken to have stopped once they gave nothing for this long: six of their ticks. */
        static final long STALLED_MS = 60;

        UplinkMixer(Out out) { this(out, () -> System.nanoTime() / 1_000_000); }

        UplinkMixer(Out out, java.util.function.LongSupplier clock) { this.out = out; this.clock = clock; }

        synchronized void solo(String member) { solo = member; }

        /** Makes [member] ride on the others' ticks; null ends it and forgets what was waiting. */
        synchronized void ride(String member) {
            rider = member;
            if (member == null) riding.clear();
        }

        /** The rider's blocks not yet gone out. */
        synchronized int waiting() { return riding.size(); }

        synchronized void add(String member) { members.add(member); }

        synchronized void remove(String member) {
            members.remove(member);
            given.remove(member);
            if (member.equals(rider)) { rider = null; riding.clear(); }
            if (!given.isEmpty() && complete()) flush();
        }

        /** Every member with a beat of its own has given its block for this tick. */
        private boolean complete() {
            for (String m : members) if (!m.equals(rider) && !given.contains(m)) return false;
            return true;
        }

        synchronized int size() { return members.size(); }

        synchronized void mix(String member, short[] block) {
            if (!members.contains(member)) return;
            if (member.equals(rider)) {
                riding.add(block);
                // Nobody else to take the beat from, or the others have stopped giving (a phone still connecting, or
                // gone quiet): it goes out by its own, as a stalled phone does not hold the rest back.
                if (members.size() == 1 || clock.getAsLong() - lastBeat > STALLED_MS) flush();
                return;
            }
            lastBeat = clock.getAsLong();
            if (given.contains(member)) flush();
            if (acc == null) acc = new int[block.length];
            else if (acc.length < block.length) acc = java.util.Arrays.copyOf(acc, block.length);
            if (solo != null && !solo.equals(member)) for (int i = 0; i < block.length; i++) acc[i] += block[i] / 4;
            else for (int i = 0; i < block.length; i++) acc[i] += block[i];
            given.add(member);
            if (complete()) flush();
        }

        private void flush() {
            short[] ridden = riding.poll();
            if (ridden != null) {
                if (acc == null) acc = new int[ridden.length];
                else if (acc.length < ridden.length) acc = java.util.Arrays.copyOf(acc, ridden.length);
                for (int i = 0; i < ridden.length; i++) acc[i] += ridden[i];
            }
            if (acc == null) return;
            short[] sum = new short[acc.length];
            for (int i = 0; i < acc.length; i++) sum[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, acc[i]));
            acc = null;
            given.clear();
            out.emit(sum);
        }
    }

    /**
     * Paces the phone's audio into Telephony TX (2026-11-05). TX may take audio slower or faster than its nominal rate
     * (85% of 48 kHz in some dialed calls) and WebRTC hands over audio in bursts (up to 10 blocks at once), while a
     * non-blocking write into the small TX buffer used to throw away whatever did not fit. So: resample to the rate TX
     * really consumes, hold what does not fit (up to 100 ms) and write it on the next turn, and steer the total backlog
     * (TX buffer plus held audio) gently towards 40 ms. Audio is dropped only when more than 100 ms is held.
     */
    static final class UplinkPacer {
        interface Sink {
            int write(short[] a, int off, int len);
            long head();
        }

        private final int nominal;
        private final Sink sink;
        private final Resampler resampler = new Resampler();
        private final short[] pending;
        int pendingLen;
        long written, droppedSamples;
        double rate, ratio = 1;
        private long sinceMs, sinceHead;

        UplinkPacer(int nominal, Sink sink) {
            this.nominal = nominal;
            this.sink = sink;
            this.rate = nominal;
            this.pending = new short[nominal / 10 + 4096];
        }

        long backlog() { return Math.max(0, written - sink.head()) + pendingLen; }

        void offer(short[] block, long nowMs) {
            long head = sink.head();
            if (sinceMs == 0) { sinceMs = nowMs; sinceHead = head; }
            else if (nowMs - sinceMs >= 1000) {
                double measured = (head - sinceHead) * 1000.0 / (nowMs - sinceMs);
                // The first real measurement is taken as is; later ones are smoothed.
                if (sinceHead > 0 && measured > 0) rate = measured(measured);
                sinceMs = nowMs;
                sinceHead = head;
            }
            double target = nominal * 0.04, backlog = backlog();
            // Gentle around the target; firmer when far above it, so a slow TX does not overflow the 100 ms hold.
            double gain = backlog > 2 * target ? 0.15 : 0.03;
            ratio = rate / nominal + gain * (target - backlog) / target;
            ratio = Math.max(0.75, Math.min(1.06, ratio));
            short[] out = resampler.process(block, ratio);
            int limit = nominal / 10;
            if (pendingLen + out.length > limit) {
                // More than 100 ms held: TX has stalled. Drop the oldest held audio, never the newest.
                int excess = pendingLen + out.length - limit;
                int fromPending = Math.min(excess, pendingLen);
                System.arraycopy(pending, fromPending, pending, 0, pendingLen - fromPending);
                pendingLen -= fromPending;
                droppedSamples += excess;
                int skip = excess - fromPending;
                System.arraycopy(out, skip, pending, pendingLen, out.length - skip);
                pendingLen += out.length - skip;
            } else {
                System.arraycopy(out, 0, pending, pendingLen, out.length);
                pendingLen += out.length;
            }
            flush();
        }

        private boolean measuredOnce;

        private double measured(double m) {
            if (!measuredOnce) { measuredOnce = true; return m; }
            return rate * 0.7 + m * 0.3;
        }

        void flush() {
            if (pendingLen == 0) return;
            int n = sink.write(pending, 0, pendingLen);
            if (n <= 0) return;
            System.arraycopy(pending, n, pending, 0, pendingLen - n);
            pendingLen -= n;
            written += n;
        }
    }
}

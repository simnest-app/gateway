package dev.cloudsim.gateway.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * The process-wide recording and playout switches around a call connected while it rings (ANSWER_DELAY.md 14.11): a
 * session that never opened must not start the recording, not even as its phone leaves.
 */
public class AudioSwitchesTest {
    /** Stands for the audio device module: it records while the switch is on and some connection is there. */
    static final class Adm {
        boolean on = true;
        final Set<Conn> open = new HashSet<>();
        boolean recording;
        final List<String> log = new ArrayList<>();

        void update() {
            boolean now = on && !open.isEmpty();
            if (now != recording) log.add(now ? "far-end recording started" : "far-end recording stopped");
            recording = now;
        }
    }

    static final class Conn implements BridgeMain.AudioSwitches.Switched {
        final Adm adm;

        Conn(Adm adm) { this.adm = adm; adm.open.add(this); }

        public void audio(boolean on) { adm.on = on; adm.update(); }

        public void close() { adm.open.remove(this); adm.update(); }
    }

    @Test public void aRingSessionThatNeverOpensNeverRecords() {
        Adm adm = new Adm();
        Conn owner = new Conn(adm);
        BridgeMain.AudioSwitches.join(owner, false);
        BridgeMain.AudioSwitches.close(owner);
        assertEquals(new ArrayList<String>(), adm.log);
    }

    @Test public void theOwnersConnectionLeavingKeepsTheFamilysAudio() {
        Adm adm = new Adm();
        Conn owner = new Conn(adm);
        BridgeMain.AudioSwitches.join(owner, false);
        Conn family = new Conn(adm);
        BridgeMain.AudioSwitches.join(family, true);
        assertTrue(adm.recording);
        BridgeMain.AudioSwitches.close(owner);
        assertTrue(adm.recording);
        assertEquals(List.of("far-end recording started"), adm.log);
    }

    @Test public void theNextCallRecordsAfterARingSessionLeftTheSwitchesOff() {
        Adm adm = new Adm();
        Conn ring = new Conn(adm);
        BridgeMain.AudioSwitches.join(ring, false);
        BridgeMain.AudioSwitches.close(ring);
        Conn next = new Conn(adm);
        BridgeMain.AudioSwitches.join(next, true);
        assertTrue(adm.recording);
    }

    @Test public void theAnswerOpensTheAudio() {
        Adm adm = new Adm();
        Conn owner = new Conn(adm);
        BridgeMain.AudioSwitches.join(owner, false);
        BridgeMain.AudioSwitches.open(owner);
        assertTrue(adm.recording);
        BridgeMain.AudioSwitches.close(owner);
        assertEquals(List.of("far-end recording started", "far-end recording stopped"), adm.log);
    }
}

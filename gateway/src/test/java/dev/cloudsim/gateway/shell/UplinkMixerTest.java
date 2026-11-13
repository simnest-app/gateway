package dev.cloudsim.gateway.shell;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** The uplink sum of several phones (MULTIPARTY_TRANSFER_V6.md, 5a2). */
public class UplinkMixerTest {
    private final List<short[]> out = new ArrayList<>();
    private final BridgeMain.UplinkMixer mixer = new BridgeMain.UplinkMixer(out::add);

    private static short[] block(int... v) {
        short[] b = new short[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (short) v[i];
        return b;
    }

    @Test public void onePhonePassesEachBlockStraightThrough() {
        mixer.add("a");
        mixer.mix("a", block(1, 2, 3));
        mixer.mix("a", block(4, 5, 6));
        assertEquals(2, out.size());
        assertArrayEquals(block(4, 5, 6), out.get(1));
    }

    @Test public void aTickGoesOutOnceEveryPhoneGaveOne() {
        mixer.add("a");
        mixer.add("b");
        mixer.mix("a", block(100, -100));
        assertEquals(0, out.size());
        mixer.mix("b", block(5, 5));
        assertEquals(1, out.size());
        assertArrayEquals(block(105, -95), out.get(0));
    }

    @Test public void aStalledPhoneDoesNotHoldTheOthersBack() {
        mixer.add("a");
        mixer.add("b");
        mixer.mix("a", block(1));
        mixer.mix("a", block(2)); // b gave nothing this tick: a's first block goes out alone
        assertEquals(1, out.size());
        assertArrayEquals(block(1), out.get(0));
    }

    @Test public void theSumIsClampedNotWrapped() {
        mixer.add("a");
        mixer.add("b");
        mixer.mix("a", block(30000, -30000));
        mixer.mix("b", block(30000, -30000));
        assertArrayEquals(block(32767, -32768), out.get(0));
    }

    @Test public void aPhoneLeavingCompletesTheTickWithoutIt() {
        mixer.add("a");
        mixer.add("b");
        mixer.add("c");
        mixer.mix("a", block(1));
        mixer.mix("b", block(2));
        mixer.remove("c");
        assertEquals(1, out.size());
        assertArrayEquals(block(3), out.get(0));
        mixer.mix("x", block(9)); // not a member: ignored
        assertEquals(1, out.size());
        assertEquals(2, mixer.size());
    }

    @Test public void blocksOfDifferentLengthsAreSummedOverTheLongest() {
        mixer.add("a");
        mixer.add("b");
        mixer.mix("a", block(1, 1));
        mixer.mix("b", block(1, 1, 1));
        assertArrayEquals(block(2, 2, 1), out.get(0));
    }

    /** RECORDING_NOTICE.md 3: while the notice plays, the phones are turned down to a quarter under it, and back after. */
    @Test public void theNoticeIsHeardOverThePhonesWhileItPlays() {
        mixer.add("a");
        mixer.add(BridgeMain.NOTICE);
        mixer.solo(BridgeMain.NOTICE);
        mixer.mix("a", block(4000, -4000));
        mixer.mix(BridgeMain.NOTICE, block(1000, 1000));
        assertArrayEquals(block(2000, 0), out.get(0));
        mixer.solo(null);
        mixer.remove(BridgeMain.NOTICE);
        mixer.mix("a", block(4000, -4000));
        assertArrayEquals(block(4000, -4000), out.get(1));
    }

    /** The notice has no beat of its own: one of its blocks goes out with each tick of the phone, never a tick more. */
    @Test public void theNoticeRidesOnThePhonesTicks() {
        long[] now = {0};
        List<short[]> got = new ArrayList<>();
        BridgeMain.UplinkMixer m = new BridgeMain.UplinkMixer(got::add, () -> now[0]);
        m.add("a");
        m.add(BridgeMain.NOTICE);
        m.ride(BridgeMain.NOTICE);
        m.solo(BridgeMain.NOTICE);
        m.mix("a", block(4000));
        assertEquals(1, got.size());
        // Its timer runs ahead of the phone's: three blocks while the phone gives one.
        m.mix(BridgeMain.NOTICE, block(100));
        m.mix(BridgeMain.NOTICE, block(200));
        m.mix(BridgeMain.NOTICE, block(300));
        assertEquals(1, got.size());
        now[0] = 10;
        m.mix("a", block(4000));
        now[0] = 20;
        m.mix("a", block(4000));
        assertEquals(3, got.size());
        assertArrayEquals(block(1100), got.get(1));
        assertArrayEquals(block(1200), got.get(2));
        assertEquals(1, m.waiting());
    }

    /** With no phone giving anything (still connecting, or none in the call), the notice goes out by its own. */
    @Test public void theNoticeGoesOutAloneWhenThePhonesGiveNothing() {
        long[] now = {0};
        List<short[]> got = new ArrayList<>();
        BridgeMain.UplinkMixer m = new BridgeMain.UplinkMixer(got::add, () -> now[0]);
        m.add(BridgeMain.NOTICE);
        m.ride(BridgeMain.NOTICE);
        m.mix(BridgeMain.NOTICE, block(100));
        assertEquals(1, got.size());
        m.add("a");
        m.mix("a", block(4000));
        now[0] = 10;
        m.mix(BridgeMain.NOTICE, block(200));
        assertEquals(2, got.size());
        // The phone gives nothing more: after six of its ticks the notice no longer waits for it.
        now[0] = 100;
        m.mix(BridgeMain.NOTICE, block(300));
        assertEquals(3, got.size());
        assertArrayEquals(block(200), got.get(2));
        assertEquals(1, m.waiting());
    }
}

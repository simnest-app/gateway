package dev.cloudsim.gateway.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Simulates Telephony TX as it behaved on the Redmi (2026-11-05): a 3078-frame buffer drained at a fixed real rate. */
public class UplinkPacerTest {
    static final class FakeTx implements BridgeMain.UplinkPacer.Sink {
        final int capacity;
        final double perMs;
        double head;
        long written, underrunMs;

        FakeTx(int capacity, double rate) { this.capacity = capacity; this.perMs = rate / 1000; }

        void tick() {
            double avail = written - head;
            if (avail <= 0) { if (written > 0) underrunMs++; return; }
            head += Math.min(perMs, avail);
        }

        public int write(short[] a, int off, int len) {
            int room = (int) (capacity - (written - (long) head));
            int n = Math.max(0, Math.min(room, len));
            written += n;
            return n;
        }

        public long head() { return (long) head; }
    }

    /** WebRTC delivers 48 kHz in 10 ms blocks, sometimes ten at once; TX drains at [txRate]. Returns the pacer after 60 s. */
    private static Object[] run(double txRate, int burst) {
        FakeTx tx = new FakeTx(3078, txRate);
        BridgeMain.UplinkPacer pacer = new BridgeMain.UplinkPacer(48000, tx);
        short[] block = new short[480];
        for (int i = 0; i < block.length; i++) block[i] = (short) (1000 * Math.sin(i / 10.0));
        for (int ms = 1; ms <= 60_000; ms++) {
            tx.tick();
            if (ms % (10 * burst) == 0) for (int b = 0; b < burst; b++) pacer.offer(block, ms);
            else if (ms % 10 == 0) pacer.flush();
        }
        return new Object[] {pacer, tx};
    }

    @Test
    public void slowTxInBurstsLosesNothingAndSettlesNearFortyMs() {
        Object[] r = run(48000 * 0.85, 10);
        BridgeMain.UplinkPacer pacer = (BridgeMain.UplinkPacer) r[0];
        FakeTx tx = (FakeTx) r[1];
        assertEquals("dropped samples", 0, pacer.droppedSamples);
        assertEquals(0.85, pacer.rate / 48000, 0.01);
        // After settling the backlog stays bounded; TX starves only at the start and between 100 ms bursts at most briefly.
        assertTrue("backlog " + pacer.backlog(), pacer.backlog() < 4800);
        assertTrue("underrun ms " + tx.underrunMs, tx.underrunMs < 600);
    }

    @Test
    public void nominalTxSteadyBlocksStayClean() {
        Object[] r = run(48000, 1);
        BridgeMain.UplinkPacer pacer = (BridgeMain.UplinkPacer) r[0];
        FakeTx tx = (FakeTx) r[1];
        assertEquals(0, pacer.droppedSamples);
        assertEquals(1.0, pacer.ratio, 0.03);
        assertTrue("underrun ms " + tx.underrunMs, tx.underrunMs < 200);
    }

    @Test
    public void aStalledTxDropsOnlyBeyondOneHundredMs() {
        FakeTx tx = new FakeTx(3078, 0);
        BridgeMain.UplinkPacer pacer = new BridgeMain.UplinkPacer(48000, tx);
        for (int b = 0; b < 100; b++) pacer.offer(new short[480], b * 10L + 1);
        assertTrue(pacer.pendingLen <= 4800);
        assertTrue(pacer.droppedSamples > 0);
    }
}

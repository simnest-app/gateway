package dev.cloudsim.gateway.shell;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** A recording's two sides at 16 kHz, side by side (RECORDING_V7.md 2-3). */
public class RecordTapTest {
    private static short[] fill(int n, int v) {
        short[] b = new short[n];
        java.util.Arrays.fill(b, (short) v);
        return b;
    }

    @Test public void eightKilohertzIsDoubled() {
        RecordTap tap = new RecordTap();
        // 100 ms at 8 kHz, twice: the far side's whole first block is there.
        tap.addFar(fill(800, 1000), 800, 8000);
        tap.addFar(fill(800, 1000), 800, 8000);
        short[] out = tap.tick();
        assertEquals(RecordTap.CHUNK * 2, out.length);
        assertEquals(0, tap.farMissing);
        // The very first sample leans on the silence before it; after that the level is the input's.
        assertEquals(1000, out[2 * 10]);
        assertEquals(1000, out[2 * (RecordTap.CHUNK - 1)]);
    }

    @Test public void fortyEightKilohertzIsAveragedDownByThree() {
        RecordTap tap = new RecordTap();
        short[] in = new short[4800];
        for (int i = 0; i < in.length; i++) in[i] = (short) (i % 3 == 0 ? 300 : 0);
        tap.addNear(in, in.length, 48000);
        short[] out = tap.tick();
        assertEquals(0, tap.nearMissing);
        for (int i = 0; i < RecordTap.CHUNK; i++) assertEquals(100, out[i * 2 + 1]);
    }

    @Test public void aSideThatSentNothingIsSilentAndCounted() {
        RecordTap tap = new RecordTap();
        tap.addNear(fill(4800, 7), 4800, 48000);
        short[] out = tap.tick();
        assertEquals(RecordTap.CHUNK, tap.farMissing);
        assertEquals(0, out[0]);
        assertEquals(7, out[1]);
    }

    @Test public void aSideTooFarAheadLosesItsOldest() {
        RecordTap tap = new RecordTap();
        // One second at 16 kHz arrives at once: only the newest 300 ms is kept.
        short[] in = new short[16000];
        for (int i = 0; i < in.length; i++) in[i] = (short) (i / 1000);
        tap.addNear(in, in.length, 16000);
        short[] first = tap.tick();
        // 16000 in, minus the one sample held back for interpolation, keeps samples 15999-4800 .. 15998.
        assertEquals((15999 - RecordTap.MAX) / 1000, first[1]);
        tap.tick();
        tap.tick();
        tap.tick();
        assertEquals(RecordTap.CHUNK, tap.nearMissing);
    }

    @Test public void oddRatesRunOnAcrossBlocks() {
        RecordTap.Resampler r = new RecordTap.Resampler();
        int[] count = {0};
        // 44.1 kHz in 10 ms blocks for one second: about 16000 out, whatever the block edges.
        for (int b = 0; b < 100; b++) r.run(fill(441, 5), 441, 44100, s -> count[0]++);
        assertEquals(16000, count[0], 2);
    }
}

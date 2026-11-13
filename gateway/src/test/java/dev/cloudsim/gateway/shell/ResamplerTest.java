package dev.cloudsim.gateway.shell;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ResamplerTest {
    private static short[] sine(int from, int n, double hz) {
        short[] s = new short[n];
        for (int i = 0; i < n; i++) s[i] = (short) Math.round(10000 * Math.sin(2 * Math.PI * hz * (from + i) / 48000.0));
        return s;
    }

    @Test
    public void ratioOneLeavesAudioUntouched() {
        short[] in = sine(0, 480, 440);
        assertArrayEquals(in, new BridgeMain.Resampler().process(in, 1.0));
    }

    /** 85% of 48 kHz, as TX consumed in the dialed call: one second in gives 0.85 s out, continuous across blocks. */
    @Test
    public void slowerTxGetsFewerSamplesWithoutGaps() {
        BridgeMain.Resampler r = new BridgeMain.Resampler();
        int total = 0, maxStep = 0;
        short last = 0;
        boolean first = true;
        for (int b = 0; b < 100; b++) {
            short[] out = r.process(sine(b * 480, 480, 440), 0.85);
            for (short v : out) {
                if (!first) maxStep = Math.max(maxStep, Math.abs(v - last));
                last = v;
                first = false;
            }
            total += out.length;
        }
        assertEquals(40800, total, 3);
        // A 440 Hz sine resampled to 40.8 kHz moves at most ~680 per sample; a seam between blocks would jump far more.
        assertTrue("max step " + maxStep, maxStep < 800);
    }

    @Test
    public void ratioMayChangeBetweenBlocks() {
        BridgeMain.Resampler r = new BridgeMain.Resampler();
        int total = 0;
        for (int b = 0; b < 50; b++) total += r.process(sine(b * 480, 480, 300), 0.8).length;
        for (int b = 50; b < 100; b++) total += r.process(sine(b * 480, 480, 300), 1.02).length;
        assertEquals(24000 * 0.8 + 24000 * 1.02, total, 4);
    }
}

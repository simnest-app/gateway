package dev.cloudsim.gateway.shell;

/**
 * The call's two sides for a recording (RECORDING_V7.md 2-3): the cellular party (the captured downlink) and this side
 * (the phones' audio as mixed into the uplink), each brought to 16 kHz and handed out together every 100 ms as one
 * stereo block, left the far party, right this side. Whatever did not arrive in time is silence; more than 300 ms
 * waiting on one side is dropped from its oldest end, so the two sides never drift apart by more than that.
 */
final class RecordTap {
    static final int RATE = 16000;
    /** Frames in one block handed out: 100 ms. */
    static final int CHUNK = RATE / 10;
    /** Frames one side may hold back before its oldest are dropped: 300 ms. */
    static final int MAX = CHUNK * 3;

    private final Side far = new Side(), near = new Side();
    /** Frames filled with silence because a side had nothing, per side: a gap in that side's audio. */
    long farMissing, nearMissing;

    synchronized void addFar(short[] mono, int len, int rate) { far.add(mono, len, rate); }

    synchronized void addNear(short[] mono, int len, int rate) { near.add(mono, len, rate); }

    /** The next 100 ms, interleaved left (far) and right (near). */
    synchronized short[] tick() {
        short[] out = new short[CHUNK * 2];
        farMissing += far.take(out, 0);
        nearMissing += near.take(out, 1);
        return out;
    }

    /** One side: a resampler to 16 kHz and what it produced, waiting to be taken. */
    private static final class Side {
        private final Resampler resampler = new Resampler();
        private final short[] fifo = new short[MAX];
        private int start, size;

        void add(short[] in, int len, int rate) {
            resampler.run(in, len, rate, this::push);
        }

        private void push(short s) {
            if (size == MAX) { start = (start + 1) % MAX; size--; }
            fifo[(start + size) % MAX] = s;
            size++;
        }

        /** Fills every other sample of [out] from [channel]; returns how many it had to leave silent. */
        int take(short[] out, int channel) {
            int n = Math.min(size, CHUNK);
            for (int i = 0; i < n; i++) out[i * 2 + channel] = fifo[(start + i) % MAX];
            start = (start + n) % MAX;
            size -= n;
            return CHUNK - n;
        }
    }

    /**
     * From any rate to 16 kHz, across blocks. Whole multiples (48 kHz, 32 kHz) average each group first, a crude low-pass
     * so the phones' wideband audio does not fold back into the speech band; other rates (8 kHz) interpolate linearly.
     */
    static final class Resampler {
        interface Sink { void put(short s); }

        private double t;
        private double prev;
        private int lastRate;
        private int groupSum, groupCount;

        void run(short[] x, int n, int rate, Sink out) {
            if (n <= 0 || rate <= 0) return;
            if (rate != lastRate) { t = 0; prev = 0; groupSum = 0; groupCount = 0; lastRate = rate; }
            if (rate > RATE && rate % RATE == 0) {
                int k = rate / RATE;
                for (int i = 0; i < n; i++) {
                    groupSum += x[i];
                    if (++groupCount == k) { out.put((short) (groupSum / k)); groupSum = 0; groupCount = 0; }
                }
                return;
            }
            double step = rate / (double) RATE;
            // x[-1] is the last sample of the block before, so output runs on smoothly across blocks.
            while (t < n - 1) {
                int i = (int) Math.floor(t);
                double f = t - i;
                double a = i < 0 ? prev : x[i];
                double b = x[i + 1];
                out.put((short) Math.round(a + (b - a) * f));
                t += step;
            }
            t -= n;
            prev = x[n - 1];
        }
    }
}

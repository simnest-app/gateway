package dev.cloudsim.gateway.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** The earpiece silence between the answer and the media (QuietHold): kept, handed over, and always given back. */
public class QuietHoldTest {
    /** Stands for BridgeMain.Silence: a volume something else can raise again, which enforce() puts back to 0. */
    static final class FakeSilence implements QuietHold.Silenced {
        final AtomicInteger enforced = new AtomicInteger();
        volatile int volume;
        volatile boolean disabled;

        public void enforce() {
            if (disabled) return;
            enforced.incrementAndGet();
            volume = 0;
        }

        public void disable() { disabled = true; volume = 5; }
    }

    private static void waitFor(java.util.function.BooleanSupplier ok, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!ok.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(5);
    }

    @Test
    public void keepsAssertingUntilTheSessionTakesIt() throws Exception {
        QuietHold<FakeSilence> hold = new QuietHold<>(10, 60_000);
        FakeSilence s = new FakeSilence();
        hold.quiet(() -> s);
        // The phone raises the call volume as the call goes active (the Mate 40 Pro+): put back by the next tick.
        s.volume = 5;
        waitFor(() -> s.volume == 0, 1000);
        assertEquals(0, s.volume);
        waitFor(() -> s.enforced.get() >= 3, 1000);
        assertTrue(s.enforced.get() >= 3);

        assertSame(s, hold.take());
        assertFalse(hold.holding());
        assertFalse(hold.keeping());
        // Handed over: the session's own watch keeps it from here, not this keeper; still silent, not restored.
        int after = s.enforced.get();
        Thread.sleep(60);
        assertTrue(s.enforced.get() <= after + 1);
        assertFalse(s.disabled);
    }

    @Test
    public void callEndedBeforeMediaGivesTheVolumeBack() throws Exception {
        QuietHold<FakeSilence> hold = new QuietHold<>(10, 60_000);
        FakeSilence s = new FakeSilence();
        hold.quiet(() -> s);
        hold.release();
        assertTrue(s.disabled);
        assertFalse(hold.keeping());
        int after = s.enforced.get();
        Thread.sleep(60);
        assertEquals(after, s.enforced.get());
        assertEquals(5, s.volume);
        assertNull(hold.take());
    }

    @Test
    public void neverLeavesThePhoneSilencedForGood() throws Exception {
        QuietHold<FakeSilence> hold = new QuietHold<>(10, 80);
        FakeSilence s = new FakeSilence();
        hold.quiet(() -> s);
        waitFor(() -> s.disabled, 2000);
        assertTrue(s.disabled);
        assertFalse(hold.holding());
        assertFalse(hold.keeping());
        // A session starting after that silences anew rather than taking a restored one.
        assertNull(hold.take());
    }

    @Test
    public void quietAgainAssertsAtOnceWithoutASecondSilence() throws Exception {
        QuietHold<FakeSilence> hold = new QuietHold<>(10_000, 60_000);
        FakeSilence s = new FakeSilence();
        AtomicInteger enabled = new AtomicInteger();
        hold.quiet(() -> { enabled.incrementAndGet(); return s; });
        s.volume = 5;
        // Sent again as the call goes active: no waiting for the next tick.
        hold.quiet(() -> { enabled.incrementAndGet(); return new FakeSilence(); });
        assertEquals(1, enabled.get());
        assertEquals(0, s.volume);
        hold.release();
    }

    @Test
    public void aFailingEnforceDoesNotStopTheKeeper() throws Exception {
        QuietHold<QuietHold.Silenced> hold = new QuietHold<>(10, 60_000);
        AtomicInteger calls = new AtomicInteger();
        hold.quiet(() -> new QuietHold.Silenced() {
            public void enforce() { if (calls.incrementAndGet() == 1) throw new IllegalStateException("rejected"); }
            public void disable() {}
        });
        waitFor(() -> calls.get() >= 3, 1000);
        assertTrue(calls.get() >= 3);
        hold.release();
    }

    @Test
    public void appGoneWithNothingHeldIsHarmless() {
        QuietHold<FakeSilence> hold = new QuietHold<>(10, 60_000);
        hold.release();
        assertNull(hold.take());
    }
}

package dev.cloudsim.gateway.shell;

/**
 * The earpiece silence of a call whose media has not started yet, kept asserted until the call's session takes it.
 *
 * The silence used to be set once, at the "quiet" sent before the answer (or the dial), and checked again only once
 * the first offer had arrived and a session was running. The Mate 40 Pro+ played the far end in its earpiece in
 * between, 2.6 to 10 s on 2026-11-13 while the iPhone's offer was on its way; the Redmi did not. Re-asserted here
 * every [periodMs], so whatever the phone does to the call volume as the call starts is undone at once.
 *
 * It ends when the session takes the silence over ([take]), when the call stops or the app goes away ([release]), or
 * at the latest [limitMs] after it began, restoring the volume: a phone is never left silenced by a call nobody
 * stopped.
 */
final class QuietHold<S extends QuietHold.Silenced> {
    /** What is held: [enforce] re-asserts it, [disable] gives the earpiece and microphone back. */
    interface Silenced {
        void enforce();
        void disable();
    }

    interface Enabler<S> { S enable() throws Exception; }

    private final long periodMs, limitMs;
    private S held;
    private Keeper keeper;

    QuietHold(long periodMs, long limitMs) {
        this.periodMs = periodMs;
        this.limitMs = limitMs;
    }

    /** A "quiet": silences now and keeps it so; when already held, asserts it again at once. */
    synchronized void quiet(Enabler<S> enabler) throws Exception {
        if (held != null) {
            held.enforce();
            return;
        }
        S s = enabler.enable();
        held = s;
        keeper = new Keeper(s);
        keeper.thread.start();
    }

    /** Hands the silence to the call's session (null when none was held); this stops asserting it. */
    synchronized S take() {
        S s = held;
        stopKeeper();
        held = null;
        return s;
    }

    /** The call stopped or the app went away before a session took it: the earpiece and microphone come back. */
    synchronized void release() {
        stopKeeper();
        S s = held;
        held = null;
        if (s != null) s.disable();
    }

    synchronized boolean holding() { return held != null; }

    synchronized boolean keeping() { return keeper != null && !keeper.stopped; }

    private void stopKeeper() {
        if (keeper == null) return;
        keeper.stopped = true;
        keeper.thread.interrupt();
        keeper = null;
    }

    private synchronized void expire(Keeper k) {
        if (keeper != k) return;
        keeper = null;
        S s = held;
        held = null;
        System.out.println("quiet expired after " + limitMs + " ms without media: volume restored");
        if (s != null) s.disable();
    }

    private final class Keeper {
        final S silence;
        final Thread thread;
        volatile boolean stopped;

        Keeper(S silence) {
            this.silence = silence;
            thread = new Thread(this::run, "quiet");
            thread.setDaemon(true);
        }

        private void run() {
            long start = System.nanoTime();
            while (!stopped) {
                try { Thread.sleep(periodMs); } catch (InterruptedException e) { if (stopped) return; }
                if (stopped) return;
                if ((System.nanoTime() - start) / 1_000_000 >= limitMs) {
                    expire(this);
                    return;
                }
                try { silence.enforce(); } catch (RuntimeException e) { System.out.println("quiet keeper " + e); }
            }
        }
    }
}

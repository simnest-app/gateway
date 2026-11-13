package com.flyfish233.crypto.spake2;

/**
 * SPAKE2 over BoringSSL (cpp/spake2.cpp), with the class name and methods Kadb's pairing calls, so Kadb pairs
 * through this instead of its GPL-3.0 default implementation. Only the interface matches; nothing of that code is used.
 */
public final class Spake2Context {
    static { System.loadLibrary("spake2"); }

    private long ptr;

    public Spake2Context(Spake2Role role, byte[] myName, byte[] theirName) {
        ptr = nativeNew(role == Spake2Role.Alice, myName, theirName);
        if (ptr == 0) throw new IllegalStateException("SPAKE2_CTX_new failed");
    }

    /** This side's message for the given password (the pairing code). */
    public synchronized byte[] generateMessage(byte[] password) {
        if (ptr == 0) throw new IllegalStateException("destroyed");
        byte[] msg = nativeGenerate(ptr, password);
        if (msg == null) throw new IllegalStateException("SPAKE2_generate_msg failed");
        return msg;
    }

    /** The shared key material, or null if the other side's message does not match (wrong code). */
    public synchronized byte[] processMessage(byte[] theirMsg) {
        if (ptr == 0 || theirMsg == null) return null;
        return nativeProcess(ptr, theirMsg);
    }

    public synchronized void destroy() {
        if (ptr != 0) nativeFree(ptr);
        ptr = 0;
    }

    private static native long nativeNew(boolean alice, byte[] myName, byte[] theirName);
    private static native byte[] nativeGenerate(long ptr, byte[] password);
    private static native byte[] nativeProcess(long ptr, byte[] theirMsg);
    private static native void nativeFree(long ptr);
}

// SPAKE2 for adb wireless-debugging pairing, from BoringSSL (the same implementation adbd itself uses).
// Backs com.flyfish233.crypto.spake2.Spake2Context, the interface Kadb's pairing calls, so the GPL-3.0 Java
// implementation it normally depends on is not needed.
#include <jni.h>
#include <openssl/curve25519.h>

namespace {
struct Bytes {
    JNIEnv *env;
    jbyteArray array;
    jbyte *data;
    jsize size;
    Bytes(JNIEnv *e, jbyteArray a) : env(e), array(a), data(e->GetByteArrayElements(a, nullptr)), size(e->GetArrayLength(a)) {}
    ~Bytes() { env->ReleaseByteArrayElements(array, data, JNI_ABORT); }
    const uint8_t *bytes() const { return reinterpret_cast<const uint8_t *>(data); }
};

jbyteArray toArray(JNIEnv *env, const uint8_t *data, size_t size) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(size));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte *>(data));
    return out;
}
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_flyfish233_crypto_spake2_Spake2Context_nativeNew(JNIEnv *env, jclass, jboolean alice, jbyteArray myName, jbyteArray theirName) {
    Bytes mine(env, myName), theirs(env, theirName);
    SPAKE2_CTX *ctx = SPAKE2_CTX_new(alice ? spake2_role_alice : spake2_role_bob, mine.bytes(), mine.size, theirs.bytes(), theirs.size);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jbyteArray JNICALL Java_com_flyfish233_crypto_spake2_Spake2Context_nativeGenerate(JNIEnv *env, jclass, jlong ptr, jbyteArray password) {
    Bytes pw(env, password);
    uint8_t msg[SPAKE2_MAX_MSG_SIZE];
    size_t len = 0;
    if (!SPAKE2_generate_msg(reinterpret_cast<SPAKE2_CTX *>(ptr), msg, &len, sizeof(msg), pw.bytes(), pw.size)) return nullptr;
    return toArray(env, msg, len);
}

JNIEXPORT jbyteArray JNICALL Java_com_flyfish233_crypto_spake2_Spake2Context_nativeProcess(JNIEnv *env, jclass, jlong ptr, jbyteArray theirMsg) {
    Bytes msg(env, theirMsg);
    uint8_t key[SPAKE2_MAX_KEY_SIZE];
    size_t len = 0;
    if (!SPAKE2_process_msg(reinterpret_cast<SPAKE2_CTX *>(ptr), key, &len, sizeof(key), msg.bytes(), msg.size)) return nullptr;
    return toArray(env, key, len);
}

JNIEXPORT void JNICALL Java_com_flyfish233_crypto_spake2_Spake2Context_nativeFree(JNIEnv *, jclass, jlong ptr) {
    SPAKE2_CTX_free(reinterpret_cast<SPAKE2_CTX *>(ptr));
}
}

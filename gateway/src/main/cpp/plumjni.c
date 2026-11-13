// Port mapping for the gateway's audio bridge (docs/architecture/DIRECT_PATHS.md), over libplum (MPL-2.0).
#include <jni.h>
#include <stdio.h>
#include <string.h>
#include "plum/plum.h"

static int ready = 0;

JNIEXPORT jint JNICALL Java_dev_cloudsim_gateway_shell_PortMap_nInit(JNIEnv *env, jclass cls) {
    if (ready) return 0;
    plum_config_t config;
    memset(&config, 0, sizeof(config));
    config.log_level = PLUM_LOG_LEVEL_WARN;
    config.discover_timeout = 6000;
    config.mapping_timeout = 6000;
    int r = plum_init(&config);
    if (r == PLUM_ERR_SUCCESS) ready = 1;
    return r;
}

JNIEXPORT jint JNICALL Java_dev_cloudsim_gateway_shell_PortMap_nCreate(JNIEnv *env, jclass cls, jint port) {
    plum_mapping_t m;
    memset(&m, 0, sizeof(m));
    m.protocol = PLUM_IP_PROTOCOL_UDP;
    m.internal_port = (uint16_t)port;
    m.external_port = (uint16_t)port; // a wish: the router may give another
    return plum_create_mapping(&m, NULL);
}

/** "state protocol port host": state 1 pending, 2 success, 3 failure; null when the id is unknown. */
JNIEXPORT jstring JNICALL Java_dev_cloudsim_gateway_shell_PortMap_nQuery(JNIEnv *env, jclass cls, jint id) {
    plum_state_t state;
    plum_mapping_t m;
    memset(&m, 0, sizeof(m));
    if (plum_query_mapping(id, &state, &m) != PLUM_ERR_SUCCESS) return NULL;
    char out[PLUM_MAX_HOST_LEN + 32];
    snprintf(out, sizeof(out), "%d %d %u %s", (int)state, (int)m.mapping_protocol, (unsigned)m.external_port, m.external_host);
    return (*env)->NewStringUTF(env, out);
}

JNIEXPORT void JNICALL Java_dev_cloudsim_gateway_shell_PortMap_nDestroy(JNIEnv *env, jclass cls, jint id) {
    plum_destroy_mapping(id);
}

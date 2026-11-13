package dev.cloudsim.gateway

import dev.cloudsim.gateway.net.ServiceConfig

/** Build-time facts: the version shown on screen, and the control service this build talks to. */
object BuildInfo {
    fun summary(name: String = BuildConfig.VERSION_NAME, code: Int = BuildConfig.VERSION_CODE): String = L.t("版本 %1\$s（%2\$s）", name, code)

    val service = ServiceConfig(
        address = BuildConfig.SERVICE_ADDRESS,
        certSha256 = BuildConfig.SERVICE_CERT_SHA256.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
        serviceId = BuildConfig.SERVICE_ID,
        movedTo = BuildConfig.SERVICE_MOVED_TO,
    )
}

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing stays outside the repository: ~/.cloudsim/gateway-release-signing/signing.properties
// (storeFile, storePassword, keyAlias, keyPassword), the key made for public releases on 2026-11-13 and never to be
// changed after the first one: updates are only accepted from it. Without it only debug builds are signed.
val signingFile = File(System.getProperty("user.home"), ".cloudsim/gateway-release-signing/signing.properties")
val signing = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use(::load) }

// Control service address, certificate pin and service ID, also outside the repository:
// ~/.cloudsim/service/<name>.properties (address, cert_sha256, service_id, and moved_to: the domain name host:port the
// service moved to, tried first, SERVER_LAYOUT.md); choose with -Pcloudsim.service=<name>.
val serviceName = providers.gradleProperty("cloudsim.service").getOrElse("m1")
val serviceFile = File(System.getProperty("user.home"), ".cloudsim/service/$serviceName.properties")
val service = Properties().apply { if (serviceFile.exists()) serviceFile.inputStream().use(::load) }
fun quoted(key: String) = "\"" + service.getProperty(key, "") + "\""

// Which source this build was made from, so that evidence taken from a phone (tools/evidence.py) can be matched to a
// commit: the commit's short hash, with "+changes" when files differed from it; "unknown" outside a git checkout.
fun git(vararg args: String): String = runCatching {
    providers.exec { commandLine("git", *args); isIgnoreExitValue = true }.standardOutput.asText.get().trim()
}.getOrDefault("")
val buildCommit = git("rev-parse", "--short=12", "HEAD").ifEmpty { "unknown" }.let { if (it != "unknown" && git("status", "--porcelain", "--", ".").isNotEmpty()) "$it+changes" else it }

android {
    namespace = "dev.cloudsim.gateway"
    // AndroidX 1.19 / Compose 1.12 need API 37 to compile against; runtime behaviour follows targetSdk.
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        // The public name (the user's 2026-11-13 decision); the code keeps its dev.cloudsim.gateway namespace.
        applicationId = "app.simnest.gateway"
        minSdk = 29
        targetSdk = 36
        versionCode = 107
        versionName = "0.43.2"
        // The Redmi gateway is arm64; WebRTC's native library is large, so ship that one ABI only.
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "SERVICE_ADDRESS", quoted("address"))
        buildConfigField("String", "SERVICE_CERT_SHA256", quoted("cert_sha256"))
        buildConfigField("String", "SERVICE_ID", quoted("service_id"))
        buildConfigField("String", "SERVICE_MOVED_TO", quoted("moved_to"))
        buildConfigField("String", "BUILD_COMMIT", "\"$buildCommit\"")
        // Where new versions are published (owner/repository on each); empty for one not set up yet.
        buildConfigField("String", "UPDATE_GITHUB", "\"" + providers.gradleProperty("cloudsim.update.github").getOrElse("simnest-app/gateway") + "\"")
        buildConfigField("String", "UPDATE_GITEE", "\"" + providers.gradleProperty("cloudsim.update.gitee").getOrElse("simnest-app/gateway") + "\"")
    }

    signingConfigs {
        if (signing.isNotEmpty()) {
            create("release") {
                storeFile = File(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    ndkVersion = "29.0.14206865"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    packaging {
        // The shell audio bridge runs under app_process and loads WebRTC by path from the extracted library directory.
        jniLibs { useLegacyPackaging = true }
        // The three Bouncy Castle jars Kadb uses carry the same licence file.
        resources { pickFirsts += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md") }
    }

    lint {
        // What was there on 2026-11-13 is listed in the baseline; anything new fails the build. Above all "NewApi":
        // a call to something newer than the oldest Android this is installed on only fails on such a phone
        // (the update check did, on Android 12).
        baseline = file("lint-baseline.xml")
        abortOnError = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
        prefab = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.zxing.core)
    implementation(libs.stream.webrtc)
    // Kadb's pairing normally pulls a GPL-3.0 SPAKE2 library from JitPack; it is excluded and replaced by
    // com.flyfish233.crypto.spake2 in this module, backed by BoringSSL (docs/architecture/DEPENDENCY_SOURCE_REVIEW.md).
    implementation(libs.kadb) { exclude(group = "com.github.Flyfish233") }
    implementation(libs.boringssl)
    implementation(libs.tink.android)
    implementation(libs.commons.compress)
    // sherpa-onnx 1.13.8 (Apache-2.0): voice cloning and transcription on the gateway. Not on Maven: fetched with its
    // checksum by scripts/fetch-deps.sh.
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))
    testImplementation(libs.junit)
    // Android's org.json is a stub in local unit tests.
    testImplementation(libs.org.json)
}

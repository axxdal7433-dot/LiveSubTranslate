import java.net.HttpURLConnection
import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val sherpaAarFile = file("libs/sherpa-onnx.aar")

tasks.register("downloadSherpaOnnxAar") {
    description = "Downloads prebuilt sherpa-onnx Android AAR from GitHub Releases if not present"
    outputs.file(sherpaAarFile)

    doLast {
        if (sherpaAarFile.exists() && sherpaAarFile.length() > 1_000_000L) {
            println("sherpa-onnx.aar already exists (${sherpaAarFile.length() / 1024} KB), skipping download.")
            return@doLast
        }
        sherpaAarFile.parentFile.mkdirs()

        val pinnedVersion = project.findProperty("sherpaVersion")?.toString()?.trim().orEmpty()
        val candidateUrls = mutableListOf<String>()

        if (pinnedVersion.isNotEmpty()) {
            val cleanVer = pinnedVersion.removePrefix("v")
            candidateUrls.add("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$cleanVer/sherpa-onnx-$cleanVer.aar")
        } else {
            // Query latest release from GitHub API to dynamically locate the standard Android AAR asset
            try {
                val apiConn = (URL("https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/latest")
                    .openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "LiveSubTranslate-Gradle")
                }
                if (apiConn.responseCode == 200) {
                    val json = apiConn.inputStream.bufferedReader().use { it.readText() }
                    val regex = Regex("""https://github\.com/k2-fsa/sherpa-onnx/releases/download/[^"]+/sherpa-onnx-[0-9]+\.[0-9]+\.[0-9]+\.aar""")
                    regex.find(json)?.value?.let { latestUrl ->
                        candidateUrls.add(latestUrl)
                    }
                }
            } catch (e: Exception) {
                println("Notice: Could not query GitHub latest release API (${e.message}), trying fallback URLs.")
            }
        }

        // Verified fallback URLs (official GitHub Releases + HuggingFace + China HF-Mirror)
        val ghVersions = listOf("2.0.3", "1.12.35", "1.12.30", "1.12.29", "1.12.23")
        for (ver in ghVersions) {
            candidateUrls.add("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$ver/sherpa-onnx-$ver.aar")
        }
        val hfVersions = listOf("1.12.35", "1.12.30", "1.12.29")
        for (ver in hfVersions) {
            candidateUrls.add("https://huggingface.co/csukuangfj2/sherpa-onnx-libs/resolve/main/android/aar/sherpa-onnx-$ver.aar")
            candidateUrls.add("https://hf-mirror.com/csukuangfj2/sherpa-onnx-libs/resolve/main/android/aar/sherpa-onnx-$ver.aar")
        }

        var downloaded = false
        val tempFile = file("libs/sherpa-onnx.aar.tmp")
        for (urlStr in candidateUrls.distinct()) {
            try {
                println("Downloading sherpa-onnx AAR from: $urlStr")
                var currentUrl = urlStr
                var redirects = 0
                var conn: HttpURLConnection
                while (true) {
                    conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 20_000
                        readTimeout = 60_000
                        setRequestProperty("User-Agent", "LiveSubTranslate-Gradle")
                    }
                    val code = conn.responseCode
                    if (code in listOf(301, 302, 303, 307, 308)) {
                        val loc = conn.getHeaderField("Location") ?: break
                        currentUrl = URL(URL(currentUrl), loc).toString()
                        redirects++
                        if (redirects > 8) break
                    } else {
                        break
                    }
                }

                if (conn.responseCode == 200) {
                    conn.inputStream.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (tempFile.exists() && tempFile.length() > 1_000_000L) {
                        if (sherpaAarFile.exists()) sherpaAarFile.delete()
                        tempFile.renameTo(sherpaAarFile)
                        println("Successfully downloaded sherpa-onnx.aar (${sherpaAarFile.length() / 1024} KB)")
                        downloaded = true
                        break
                    }
                }
            } catch (e: Exception) {
                println("Download attempt failed for $urlStr: ${e.message}")
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
        }

        if (!downloaded) {
            throw GradleException(
                "Failed to download sherpa-onnx.aar automatically. " +
                    "Please download sherpa-onnx-<version>.aar from https://github.com/k2-fsa/sherpa-onnx/releases " +
                    "and place it at app/libs/sherpa-onnx.aar"
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn("downloadSherpaOnnxAar")
}

android {
    namespace = "com.livesub.translator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.livesub.translator"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sign release with debug key by default so GitHub Actions produces an immediately installable APK
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf("lib/**/libonnxruntime.so", "lib/**/libsherpa-onnx-jni.so")
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(files(sherpaAarFile) {
        builtBy("downloadSherpaOnnxAar")
    })

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Google ML Kit On-Device Offline Translation
    implementation("com.google.mlkit:translate:17.0.3")

    // Pure-Java .tar.bz2 extraction for Sherpa-ONNX offline speech models
    implementation("org.apache.commons:commons-compress:1.26.2")
}

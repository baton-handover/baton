import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.baton"
    compileSdk = 35
    // AAOS 표준 Car API(CarPropertyManager) 사용
    useLibrary("android.car")

    defaultConfig {
        applicationId = "app.baton"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    // 사용자 공용 debug.keystore와 분리된 프로젝트 전용 디버그 서명
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("probe-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // 제출(release) 서명 키는 저장소 밖에 둔다. 파일이 없으면 서명 없이 빌드된다
        val props = rootProject.file("../baton-release-key/keystore.properties")
        if (props.exists()) create("release") {
            val p = Properties().apply { props.inputStream().use { load(it) } }
            storeFile = file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") { signingConfig = signingConfigs.findByName("release") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    // 운행 목록 화면 QR (차량에 이메일·공유 대상이 없어 내보내기를 화면 QR로 대신함, E 피드백 2절)
    implementation("com.google.zxing:core:3.5.3")
}
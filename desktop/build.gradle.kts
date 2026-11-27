import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
    id("org.jetbrains.compose") version "1.9.3"
}
kotlin {
    jvmToolchain(17)
    sourceSets.main { kotlin.srcDir("../shared/src/main/kotlin") }
}
sourceSets.main { resources.srcDir("../shared/src/main/resources") }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "com.local.protonawg.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "WarpProtonAWG"
            packageVersion = "2.0.0"
            modules("java.desktop", "java.net.http", "java.naming", "java.logging", "jdk.crypto.ec")
        }
    }
}

plugins {
    kotlin("jvm") version "2.2.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
    id("org.jetbrains.compose") version "1.9.3"
}
kotlin {
    jvmToolchain(17)
    sourceSets.main { kotlin.srcDirs("../shared/core/src/main/kotlin", "../shared/ui/src/main/kotlin") }
    sourceSets.test { kotlin.srcDir("../shared/core/src/test/kotlin") }
}
sourceSets.main { resources.srcDir("../shared/core/src/main/resources") }

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
            packageName = "WarpProtonAWG"
            packageVersion = "2.0.0"
            modules("java.desktop", "java.net.http", "java.naming", "java.logging", "jdk.crypto.ec")
        }
    }
}

// Package the application image, not an installer. The bundled runtime travels with the EXE.
tasks.register<Zip>("portableZip") {
    dependsOn("createDistributable")
    val image = layout.buildDirectory.dir("compose/binaries/main/app/WarpProtonAWG")
    from(image) { into("WarpProtonAWG") }
    destinationDirectory.set(layout.buildDirectory.dir("portable"))
    archiveFileName.set("WarpProtonAWG-${providers.environmentVariable("DESKTOP_VERSION").orElse("2.0.0").get()}-windows-x64.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    doFirst {
        require(image.get().file("WarpProtonAWG.exe").asFile.isFile) { "Windows launcher missing" }
        require(image.get().file("runtime/bin/java.dll").asFile.isFile) { "Bundled Java runtime missing" }
    }
}

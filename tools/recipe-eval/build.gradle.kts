plugins {
    kotlin("jvm") version "2.4.20"
    application
}

dependencies {
    // Same API and version as the app's litertlm-android; this artifact bundles the Windows/Linux/macOS natives.
    implementation("com.google.ai.edge.litertlm:litertlm-jvm:0.17.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// The recipe pipeline and eval core are compiled straight from the app, so the PC runs exactly the shipped code.
sourceSets {
    main {
        kotlin {
            srcDir("../../app/src/main/java")
            srcDir("../../app/src/androidTest/java")
            srcDir("src")
            include("com/nila/launcher/skills/**")
            exclude("**/*Test.kt")
        }
    }
}

application {
    mainClass.set("com.nila.launcher.skills.DesktopEvalKt")
}

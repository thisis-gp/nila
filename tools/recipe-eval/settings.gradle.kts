// Standalone build: runs the App Builder eval on a PC with desktop LiteRT-LM, so the phone stays free.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "recipe-eval"

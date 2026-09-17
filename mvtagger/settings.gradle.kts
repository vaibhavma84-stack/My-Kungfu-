pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        // NewPipeExtractor, which is the part of NewPipe that knows how to ask
        // YouTube for a stream. It is published nowhere else.
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "MVTagger"
include(":core")
include(":app")

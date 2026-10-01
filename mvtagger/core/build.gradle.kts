plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Deliberately a plain JVM module with no Android on the classpath. Everything
// that is genuinely hard -- rewriting MP4 metadata atoms, untangling a
// downloaded filename, scoring search results, picking a language -- lives here
// so it can be run and tested on a laptop or a CI runner with no emulator and
// no Android SDK at all.
//
// Java 17 bytecode, which is what the Android plugin needs, but no toolchain is
// pinned: that would demand exactly JDK 17 be installed, and this has to build
// on whatever a developer happens to have as well as on CI.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

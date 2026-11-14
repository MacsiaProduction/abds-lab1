plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    application
}

repositories { mavenCentral() }

kotlin {
    jvmToolchain(27)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_26) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_26
    targetCompatibility = JavaVersion.VERSION_26
}

ktlint { version.set(libs.versions.ktlint.get()) }

dependencies {
    implementation(platform(libs.kotlin.bom))
    implementation(platform(libs.ktor.bom))
    implementation(platform(libs.netty.bom))
    implementation(libs.ktor.netty)
    implementation(libs.ktor.negotiation)
    implementation(libs.ktor.json)
    implementation(libs.ktor.status)
    implementation(libs.serialization)
    implementation(libs.coroutines)
    implementation(libs.logback)
    implementation(libs.slf4j)
    constraints {
        implementation(libs.annotations)
        implementation(libs.typesafe.config)
        implementation(libs.okio)
        implementation(libs.okio.jvm)
        implementation(libs.kaml)
        implementation(libs.kaml.jvm)
        implementation(libs.snakeyaml) { version { strictly(libs.versions.snakeyaml.get()) } }
        implementation(libs.snakeyaml.jvm) { version { strictly(libs.versions.snakeyaml.get()) } }
        implementation(libs.io.core)
        implementation(libs.io.bytestring)
    }
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.ktor.test)
    testRuntimeOnly(libs.junit.launcher)
}

application { mainClass.set("lab.MainKt") }
tasks.test { useJUnitPlatform() }
dependencyLocking { lockAllConfigurations() }

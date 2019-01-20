import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.3.11"
}

group = "disklrucache"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib", KotlinCompilerVersion.VERSION))
    implementation("com.squareup.okio:okio:2.2.1")

    testImplementation("commons-io:commons-io:2.6")
    testImplementation("junit:junit:4.12")
    testImplementation("org.assertj:assertj-core:3.11.1")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.6"
}
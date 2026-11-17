plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.spring") version "1.9.22"
    `maven-publish`
    id("dev.arbjerg.lavalink.gradle-plugin") version "1.1.2"
}

group = "com.github.ALLAY-XD-20"
version = "1.0.1"

lavalinkPlugin {
    name = "lava-cluster"
    path = "dev.its2pawan.lavacluster"
    apiVersion = "4.0.8"
    serverVersion = "4.0.8"
    configurePublishing = true
}

base {
    archivesName.set("lava-cluster")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
    maven("https://maven.lavalink.dev/releases")
    maven("https://maven.lavalink.dev/snapshots")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("dev.arbjerg.lavalink:plugin-api:4.0.8")
    compileOnly("org.springframework.boot:spring-boot:3.2.5")
    compileOnly("org.springframework:spring-context:6.1.6")
    compileOnly("org.springframework:spring-web:6.1.6")
    compileOnly("jakarta.annotation:jakarta.annotation-api:2.1.1")
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    compileOnly("org.slf4j:slf4j-api:2.0.13")
}

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import io.izzel.taboolib.gradle.Basic
import io.izzel.taboolib.gradle.BukkitNMS
import io.izzel.taboolib.gradle.BukkitFakeOp
import io.izzel.taboolib.gradle.BukkitNMSEntityAI
import io.izzel.taboolib.gradle.BukkitNMSItemTag
import io.izzel.taboolib.gradle.BukkitNMSUtil
import io.izzel.taboolib.gradle.BukkitNavigation
import io.izzel.taboolib.gradle.BukkitUI
import io.izzel.taboolib.gradle.BukkitUtil
import io.izzel.taboolib.gradle.CommandHelper
import io.izzel.taboolib.gradle.MinecraftChat
import io.izzel.taboolib.gradle.Bukkit
import io.izzel.taboolib.gradle.JavaScript
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    application
    id("io.izzel.taboolib") version "2.0.36"
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
}

taboolib {
    env {
        install(Basic)
        install(BukkitNMS)
        install(BukkitFakeOp)
        install(BukkitNMSEntityAI)
        install(BukkitNMSItemTag)
        install(BukkitNMSUtil)
        install(BukkitNavigation)
        install(BukkitUI)
        install(BukkitUtil)
        install(CommandHelper)
        install(MinecraftChat)
        install(Bukkit)
        install(JavaScript)
    }
    description {
        name = "Minecraft-Websocekt"
        contributors {
            name("CyanTachyon")
        }
    }
    version { taboolib = "6.3.0-932e79c" }
    relocate("kotlinx.serialization", "moe.tachyon.mcws.kotlinx.serialization")
    relocate("org.java-websocket", "moe.tachyon.mcws.org.java_websocket")
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("ink.ptms.core:v12101:12101:mapped")
    compileOnly("ink.ptms.core:v12101:12101:universal")
    compileOnly("ink.ptms.core:v11701:11701:mapped")
    compileOnly("ink.ptms.core:v11701:11701:universal")
    taboo(kotlin("stdlib"))
    taboo("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0") { isTransitive = false }
    taboo("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0") { isTransitive = false }
    taboo("org.java-websocket:Java-WebSocket:1.6.0")
    taboo("com.github.luben:zstd-jni:1.5.7-8")

    compileOnly(fileTree("libs"))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    freeCompilerArgs.set(listOf("-Xcontext-parameters"))
}
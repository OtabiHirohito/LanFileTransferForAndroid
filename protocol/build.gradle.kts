import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

group = "com.lanfiletransfer"
version = "1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

// 開発用: Windows 版アプリとの相互接続を確認するための CLI。
//   gradlew :protocol:runInteropCli --args="send 127.0.0.1 5000 file.bin [合言葉]"
//   gradlew :protocol:runInteropCli --args="serve 5000 ./received [合言葉]"
tasks.register<JavaExec>("runInteropCli") {
    group = "verification"
    description = "Run the LFT2 protocol CLI (send/serve) for interoperability checks."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.lanfiletransfer.protocol.InteropCliKt")
    standardInput = System.`in`
}

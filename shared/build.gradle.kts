plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvmToolchain(21)
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    android {
        namespace = "dev.thehenrylabs.aetherenglish.shared"
        compileSdk = 37
        minSdk = 24
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    val host = System.getProperty("os.name")
    if (host.startsWith("Mac")) {
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("io.ktor:ktor-client-core:3.6.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
                implementation("io.ktor:ktor-client-mock:3.6.0")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-cio:3.6.0")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-okhttp:3.6.0")
                implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
                implementation("net.zetetic:sqlcipher-android:4.19.1")
                implementation("androidx.sqlite:sqlite:2.7.0")
            }
        }
        val iosMain by creating {
            dependsOn(commonMain)
        }
        if (host.startsWith("Mac")) {
            val iosArm64Main by getting { dependsOn(iosMain) }
            val iosSimulatorArm64Main by getting { dependsOn(iosMain) }
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty(
        "aether.contentDir",
        rootProject.projectDir.resolve("content").absolutePath,
    )
}

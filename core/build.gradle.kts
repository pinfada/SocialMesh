plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "1.8"
            }
        }
    }
    
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "core"
        }
    }

    // Configuration JVM modifiée - SANS withJava()
    jvm {
        // Suppression de withJava() qui est incompatible avec Android
        compilations.all {
            kotlinOptions.jvmTarget = "17"
        }
        
        attributes {
            attribute(org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute, 
                     org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm)
            attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Coroutines
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                // Serialization
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
                // Date/Time
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.1")
                // Logging
                implementation("io.github.microutils:kotlin-logging:3.0.5")
                // DI
                implementation("io.insert-koin:koin-core:3.4.3")
                // Crypto
                implementation("com.soywiz.korlibs.krypto:krypto:3.4.0")
            }
        }
        
        val androidMain by getting {
            dependencies {
                implementation("androidx.startup:startup-runtime:1.1.1")
                implementation("androidx.core:core-ktx:1.12.0")
            }
        }
        
        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependsOn(commonMain)
            iosX64Main.dependsOn(this)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
        }

        val jvmMain by getting {
            dependsOn(commonMain)
            dependencies {
                // Dépendances spécifiques JVM si nécessaire
            }
        }
    }
    
    // Assurez-vous que les configurations JVM sont correctement publiées
    // Cette partie est optionnelle mais recommandée
    tasks.withType<org.gradle.api.publish.maven.tasks.AbstractPublishToMaven>().configureEach {
        dependsOn(tasks.named("jvmJar"))
    }
}

android {
    namespace = "org.socialmesh.core"
    compileSdk = 34
    
    defaultConfig {
        minSdk = 24
    }
}
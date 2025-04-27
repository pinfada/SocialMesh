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
            baseName = "eventbus"
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
                implementation(project(":core"))
                
                // Coroutines
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                // Serialization
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
                // Atomic
                implementation("org.jetbrains.kotlinx:atomicfu:0.22.0")
                // DI
                implementation("io.insert-koin:koin-core:3.4.3")
            }
        }
        
        val androidMain by getting {
            dependencies {
                // Android specifics
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
    tasks.withType<org.gradle.api.publish.maven.tasks.AbstractPublishToMaven>().configureEach {
        dependsOn(tasks.named("jvmJar"))
    }
}

android {
    namespace = "org.socialmesh.eventbus"
    compileSdk = 34
    
    defaultConfig {
        minSdk = 24
    }
}
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
            baseName = "storage"
        }
    }

    // Configuration JVM modifiée - SANS withJava()
    jvm ({
        // Suppression de withJava() qui est incompatible avec Android
        compilations.all {
            kotlinOptions.jvmTarget = "17"
        }
        
        attributes {
            attribute(org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute, 
                     org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm)
            attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
        }
    })

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":core"))
                implementation(project(":eventbus"))
                
                // Coroutines
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                // Serialization
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
                // DI
                implementation("io.insert-koin:koin-core:3.4.3")
            }
        }
        
        val androidMain by getting {
            dependencies {
                // RocksDB pour Android
                implementation("org.rocksdb:rocksdbjni:7.10.2")
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
            
            dependencies {
                // Pour iOS, nous utiliserons SQLite intégré via une abstraction
            }
        }

        val jvmMain by getting {
            dependsOn(commonMain)
            dependencies {
                // RocksDB pour JVM
                implementation("org.rocksdb:rocksdbjni:7.10.2")
                // Autres dépendances spécifiques JVM si nécessaire
            }
        }
        
        val jvmTest by getting {
            dependsOn(commonMain)
            dependencies {
                // Dépendances de test JVM si nécessaire
            }
        }
    }
    
    // Assurez-vous que les configurations JVM sont correctement publiées
    tasks.withType<org.gradle.api.publish.maven.tasks.AbstractPublishToMaven>().configureEach {
        dependsOn(tasks.named("jvmJar"))
    }
}

android {
    namespace = "org.socialmesh.storage"
    compileSdk = 34
    
    defaultConfig {
        minSdk = 24
        
        // Configuration pour RocksDB
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }
    
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
}
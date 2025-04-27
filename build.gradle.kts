plugins {
    kotlin("jvm") version "1.9.21"
    application
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.3.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.21")
        classpath("org.jetbrains.kotlin:kotlin-serialization:1.9.21")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":eventbus"))
    implementation(project(":storage"))
    implementation(project(":network"))
}

application {
    mainClass.set("org.socialmesh.MainKt")
}
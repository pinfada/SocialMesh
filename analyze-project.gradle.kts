// Enregistrez ce script sous le nom analyze-project-fixed.gradle ou analyze-project-fixed.gradle.kts
// Exécutez avec: ./gradlew -q --init-script analyze-project-fixed.gradle help

initscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.21")
    }
}

gradle.allprojects {
    apply {
        plugin("project-report")
    }
    
    afterEvaluate {
        println("\n======= ANALYSE DU MODULE: ${project.path} =======")
        
        // Analyse des plugins
        println("\nPlugins:")
        plugins.forEach { plugin ->
            println("  - ${plugin.javaClass.name}")
        }
        
        // Analyse des configurations
        println("\nConfigurations importantes:")
        configurations.names.filter { 
            it.contains("implementation", ignoreCase = true) || 
            it.contains("api", ignoreCase = true) || 
            it.contains("Runtime", ignoreCase = true) ||
            it.contains("Elements", ignoreCase = true)
        }.sorted().forEach { configName ->
            println("  - $configName")
        }
        
        // Analyse des dépendances
        configurations.forEach { config ->
            if (config.name == "implementation" || config.name == "api") {
                println("\nDépendances pour ${config.name}:")
                config.dependencies.forEach { dep ->
                    if (dep is org.gradle.api.artifacts.ProjectDependency) {
                        println("  - Projet: ${dep.name} (${dep.group ?: "Aucun groupe"}:${dep.name}:${dep.version ?: "Aucune version"})")
                    } else {
                        println("  - Module: ${dep.name} (${dep.group ?: "Aucun groupe"}:${dep.name}:${dep.version ?: "Aucune version"})")
                    }
                }
            }
        }
        
        // Analyse des attributs (pour les problèmes de résolution)
        println("\nAttributs de plateforme pour les configurations de sortie:")
        configurations.filter { it.isCanBeConsumed }.forEach { config ->
            println("  Configuration: ${config.name}")
            try {
                config.attributes.keySet().forEach { attr ->
                    val value = config.attributes.getAttribute(attr)
                    println("    - ${attr.name}: $value")
                    
                    // Vérification spécifique de l'attribut de plateforme Kotlin
                    if (attr.name == "org.jetbrains.kotlin.platform.type") {
                        println("    [!] Attribut de plateforme Kotlin trouvé: $value")
                    }
                }
            } catch (e: Exception) {
                println("    Impossible d'accéder aux attributs: ${e.message}")
            }
        }
        
        // Analyse du fichier build
        val buildFile = projectDir.resolve("build.gradle") ?: projectDir.resolve("build.gradle.kts")
        if (buildFile.exists()) {
            println("\nExtrait du fichier de configuration (${buildFile.name}):")
            println("```")
            val content = buildFile.readText()
            println(if (content.length > 500) content.substring(0, 500) + "..." else content)
            println("```")
        }
    }
}

// Hook sur le projet racine pour montrer la structure globale
gradle.rootProject {
    afterEvaluate {
        println("\n======= STRUCTURE GLOBALE DU PROJET =======")
        println("Nom du projet racine: ${rootProject.name}")
        
        println("\nListe des modules:")
        allprojects.forEach { p ->
            println("  - ${p.path}")
        }
        
        println("\nAnalyse des modules problématiques:")
        allprojects.filter { it.name in listOf("storage", "network") }.forEach { p ->
            println("  Module: ${p.path}")
            println("  Configurations disponibles: ${p.configurations.names.sorted().joinToString(", ")}")
        }
        
        println("\n======= DIAGNOSTIC POUR LES ERREURS DE DÉPENDANCES =======")
        println("""
        Problème identifié: Incompatibilité d'attributs de plateforme Kotlin entre les modules
        
        Recommandations:
        1. Pour les modules ':storage' et ':network', ajoutez une cible JVM:
           
           kotlin {
               jvm {
                   withJava()
                   attributes {
                       attribute(org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute, 
                                "jvm")
                       attribute(JavaVersion.VERSION_17)
                   }
               }
           }
           
        2. Dans le projet principal, spécifiez la configuration à utiliser:
           
           dependencies {
               implementation(project(path = ":storage", configuration = "jvmRuntimeElements"))
               implementation(project(path = ":network", configuration = "jvmRuntimeElements"))
           }
        """.trimIndent())
    }
}
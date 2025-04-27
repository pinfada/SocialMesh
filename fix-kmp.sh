#!/bin/bash
# Enregistrez ce script sous le nom fix-kmp.sh
# Rendez-le exécutable avec: chmod +x fix-kmp.sh
# Exécutez-le à la racine de votre projet: ./fix-kmp.sh

set -e  # Sort en cas d'erreur

echo "==== CORRECTEUR DE PROJET KOTLIN MULTIPLATFORM ===="
echo "Détection de la structure du projet..."

# Vérifier si nous sommes à la racine du projet
if [ ! -f "settings.gradle" ] && [ ! -f "settings.gradle.kts" ]; then
    echo "Erreur: Ce script doit être exécuté à la racine du projet"
    exit 1
fi

# Déterminer les modules problématiques
MODULES=("storage" "network")

for MODULE in "${MODULES[@]}"; do
    echo -e "\n==== Analyse du module: $MODULE ===="
    
    # Vérifier si le module existe
    if [ ! -d "$MODULE" ]; then
        echo "Le module $MODULE n'existe pas. Vérification du suivant..."
        continue
    fi
    
    # Déterminer l'extension des fichiers build
    BUILD_FILE=""
    if [ -f "$MODULE/build.gradle" ]; then
        BUILD_FILE="$MODULE/build.gradle"
        SCRIPT_LANG="groovy"
    elif [ -f "$MODULE/build.gradle.kts" ]; then
        BUILD_FILE="$MODULE/build.gradle.kts"
        SCRIPT_LANG="kotlin"
    else
        echo "Aucun fichier build.gradle trouvé pour $MODULE. Vérification du suivant..."
        continue
    fi
    
    echo "Fichier de build détecté: $BUILD_FILE ($SCRIPT_LANG)"
    
    # Vérifier si le module est déjà un module KMP
    if grep -q "kotlin(\"multiplatform\")" "$BUILD_FILE" || grep -q "kotlin-multiplatform" "$BUILD_FILE"; then
        echo "Module KMP détecté!"
        
        # Vérifier si une cible JVM est déjà définie
        if grep -q "jvm" "$BUILD_FILE" && grep -q "withJava" "$BUILD_FILE"; then
            echo "Configuration JVM déjà présente. Aucune modification nécessaire."
            continue
        fi
        
        echo "Configuration JVM manquante. Préparation des modifications..."
        
        # Créer les répertoires source JVM s'ils n'existent pas
        mkdir -p "$MODULE/src/jvmMain/kotlin"
        mkdir -p "$MODULE/src/jvmTest/kotlin"
        echo "Répertoires src/jvmMain/kotlin et src/jvmTest/kotlin créés."
        
        # Créer un fichier de base JvmPlatform.kt
        PACKAGE_NAME=$(basename "$MODULE" | tr '-' '_' | tr '[:upper:]' '[:lower:]')
        cat > "$MODULE/src/jvmMain/kotlin/JvmPlatform.kt" << EOF
package org.socialmesh.$PACKAGE_NAME

/**
 * JVM platform specific implementations for ${MODULE}
 */
class JvmPlatform {
    fun getPlatformName(): String = "JVM"
}
EOF
        echo "Fichier JvmPlatform.kt créé avec une implémentation minimale."
        
        # Préparer les modifications du fichier build
        if [ "$SCRIPT_LANG" == "kotlin" ]; then
            echo "Le fichier est en Kotlin DSL. Voici les modifications à apporter manuellement:"
            echo -e "\nAjoutez ce code dans le bloc kotlin { ... } de $BUILD_FILE:"
            echo "
    jvm {
        withJava()
        attributes {
            attribute(org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute, 
                     org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm)
            attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
        }
        compilations.all {
            kotlinOptions.jvmTarget = \"17\"
        }
    }
    
    sourceSets {
        val jvmMain by getting {
            dependsOn(commonMain)
        }
        val jvmTest by getting {
            dependsOn(commonTest)
        }
    }"
        else
            echo "Le fichier est en Groovy DSL. Voici les modifications à apporter manuellement:"
            echo -e "\nAjoutez ce code dans le bloc kotlin { ... } de $BUILD_FILE:"
            echo "
    jvm {
        withJava()
        attributes {
            attribute(org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute, 
                     org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm)
            attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
        }
        compilations.all {
            kotlinOptions.jvmTarget = \"17\"
        }
    }
    
    sourceSets {
        jvmMain {
            dependsOn commonMain
        }
        jvmTest {
            dependsOn commonTest
        }
    }"
        fi
    else
        echo "Ce module n'est pas configuré comme un module Kotlin Multiplatform."
        echo "Recommandation: Convertissez d'abord le module en module KMP ou ajoutez le plugin kotlin(\"jvm\")."
    fi
done

# Modifications pour le projet principal
echo -e "\n==== Modifications pour le projet principal ===="
ROOT_BUILD_FILE=""
if [ -f "build.gradle" ]; then
    ROOT_BUILD_FILE="build.gradle"
    ROOT_SCRIPT_LANG="groovy"
elif [ -f "build.gradle.kts" ]; then
    ROOT_BUILD_FILE="build.gradle.kts"
    ROOT_SCRIPT_LANG="kotlin"
fi

echo "Fichier de build racine détecté: $ROOT_BUILD_FILE ($ROOT_SCRIPT_LANG)"

if [ "$ROOT_SCRIPT_LANG" == "kotlin" ]; then
    echo "Voici les modifications à apporter au bloc dependencies { ... } du projet principal:"
    echo "
dependencies {
    // Conserver les autres dépendances
    implementation(project(\":core\"))
    implementation(project(\":eventbus\"))
    
    // Remplacer ces lignes:
    implementation(project(path = \":storage\", configuration = \"jvmRuntimeElements\"))
    implementation(project(path = \":network\", configuration = \"jvmRuntimeElements\"))
}"
else
    echo "Voici les modifications à apporter au bloc dependencies { ... } du projet principal:"
    echo "
dependencies {
    // Conserver les autres dépendances
    implementation project(':core')
    implementation project(':eventbus')
    
    // Remplacer ces lignes:
    implementation project(path: ':storage', configuration: 'jvmRuntimeElements')
    implementation project(path: ':network', configuration: 'jvmRuntimeElements')
}"
fi

echo -e "\n==== Instructions finales ===="
echo "1. Appliquez manuellement les modifications suggérées aux fichiers build"
echo "2. Exécutez './gradlew clean' pour nettoyer les caches"
echo "3. Exécutez './gradlew build --dry-run' pour vérifier la résolution des dépendances"
echo "4. Si tout est correct, exécutez './gradlew build' pour compiler le projet"
echo -e "\nLe script a créé les répertoires source nécessaires et des fichiers de base pour les modules concernés."
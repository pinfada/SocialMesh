// Dans src/main/kotlin/org/socialmesh/Configuration.kt
package org.socialmesh

object Configuration {
    // Paramètres réseau
    const val ENABLE_WEBSOCKET_TRANSPORT = true
    const val WEBSOCKET_SERVER_PORT = 9000
    
    // Paramètres de découverte
    const val ENABLE_BOOTSTRAP_DISCOVERY = true
    val BOOTSTRAP_SERVERS = listOf(
        "localhost:9001",
        "localhost:9002"
    )
    
    // Paramètres de stockage
    const val STORAGE_DIRECTORY = "socialmesh_data"
    
    // Paramètres d'événements
    const val EVENT_BUFFER_SIZE = 1024
    
    // Identité
    const val IDENTITY_FILE = "identity.key"
}
package org.socialmesh

import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.socialmesh.core.SocialMeshContainer
import org.socialmesh.core.impl.*
import org.socialmesh.eventbus.DisruptorEventBus
import org.socialmesh.network.impl.MycelialNetworkService
import org.socialmesh.storage.impl.RocksDBStorageService

fun main() = runBlocking {
    println("Initializing SocialMesh...")
    
    // Configuration de Koin pour l'injection de dépendances
    val appModule = module {
        // Services de base
        single { DefaultLifecycleService() }
        single { InMemoryConfigurationService() }
        single { SimpleLoggingService() }
        single { Ed25519CryptoService() }
        single { LocalResourceService() }
        
        // Bus d'événements
        single { DisruptorEventBus() }
        
        // Stockage
        single { RocksDBStorageService() }
        
        // Réseau
        single { MycelialNetworkService() }
        
        // Container principal
        single { SocialMeshContainer() }
    }
    
    startKoin {
        modules(appModule)
    }
    
    // Récupération et initialisation du container
    val container = org.koin.java.KoinJavaComponent.getKoin().get<SocialMeshContainer>()
    
    if (container.initialize()) {
        println("SocialMesh initialized successfully")
        
        // Démarrage des services
        container.start()
        println("SocialMesh started")
        
        // Maintenir le programme en exécution
        println("SocialMesh is running. Press ENTER to stop.")
        readlnOrNull()
        
        // Arrêt propre
        container.shutdown()
        println("SocialMesh has been shutdown.")
    } else {
        println("Failed to initialize SocialMesh")
    }

    single { 
        DemoModule() 
    }

    val demoModule = org.koin.java.KoinJavaComponent.getKoin().get<DemoModule>()
    container.registerComponent(demoModule)

}
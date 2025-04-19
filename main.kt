package org.socialmesh

import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.socialmesh.core.SocialMeshContainer
import org.socialmesh.core.impl.DefaultLifecycleService
import org.socialmesh.core.impl.Ed25519CryptoService
import org.socialmesh.core.impl.InMemoryConfigurationService
import org.socialmesh.core.impl.LocalResourceService
import org.socialmesh.core.impl.SimpleLoggingService
import org.socialmesh.eventbus.DisruptorEventBus
import org.socialmesh.network.impl.MycelialNetworkService
import org.socialmesh.storage.impl.RocksDBStorageService
import org.koin.dsl.module

fun main(args: Array<String>) = runBlocking {
    println("Starting SocialMesh application...")

    // Création du module Koin pour l'injection de dépendances
    val appModule = module {
        // Core services
        single { DefaultLifecycleService() }
        single { InMemoryConfigurationService() }
        single { SimpleLoggingService() }
        single { Ed25519CryptoService() }
        single { LocalResourceService() }
        
        // EventBus
        single { DisruptorEventBus() }
        
        // Storage
        single { RocksDBStorageService() }
        
        // Network
        single { MycelialNetworkService() }
        
        // Main container
        single { SocialMeshContainer() }
    }

    // Initialisation de Koin
    startKoin {
        modules(appModule)
    }

    // Récupération et initialisation du container
    val container = org.koin.java.KoinJavaComponent.getKoin().get<SocialMeshContainer>()
    container.initialize()
    container.start()

    // Maintenir l'application en vie jusqu'à ce qu'on reçoive un signal d'arrêt
    Runtime.getRuntime().addShutdownHook(Thread {
        println("Shutting down SocialMesh...")
        container.shutdown()
    })

    println("SocialMesh application started successfully.")
    println("Press Ctrl+C to exit.")
    
    // Attendre indéfiniment (dans une application réelle, vous auriez une boucle principale ou un serveur)
    while (true) {
        Thread.sleep(1000)
    }
}
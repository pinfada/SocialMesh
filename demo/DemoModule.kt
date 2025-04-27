// Dans src/main/kotlin/org/socialmesh/demo/DemoModule.kt
package org.socialmesh.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.socialmesh.core.interfaces.LoggingService
import org.socialmesh.core.interfaces.SocialMeshComponent
import org.socialmesh.eventbus.EventBusProvider
import org.socialmesh.eventbus.events.SocialEvents
import org.socialmesh.eventbus.subscribeWithCallback
import org.socialmesh.network.NetworkService

class DemoModule : SocialMeshComponent, KoinComponent {
    override val componentId: String = "org.socialmesh.demo"
    override val version: String = "1.0.0"
    override val dependencies: List<String> = listOf(
        "org.socialmesh.core.logging",
        "org.socialmesh.network.mycelial"
    )
    
    private val logger: LoggingService by inject()
    private val networkService: NetworkService by inject()
    
    private val moduleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    override fun initialize(): Boolean {
        try {
            logger.info("DemoModule", "Initializing demo module")
            
            // S'abonner aux événements sociaux
            val eventBus = EventBusProvider.get()
            eventBus.subscribeWithCallback<SocialEvents.MessageEvent> { event ->
                logger.info("DemoModule", "Received message: ${event.messageId} from ${event.profileId}")
            }
            
            // Démarrer des opérations de démonstration
            moduleScope.launch {
                runDemoOperations()
            }
            
            return true
        } catch (e: Exception) {
            logger.error("DemoModule", "Failed to initialize demo module", e)
            return false
        }
    }
    
    override fun shutdown() {
        logger.info("DemoModule", "Shutting down demo module")
    }
    
    private suspend fun runDemoOperations() {
        // Obtenir l'identité locale
        val localIdentity = networkService.getLocalIdentity()
        logger.info("DemoModule", "Local peer ID: ${localIdentity.publicKey.contentToString()}")
        
        // Démarrer le service réseau
        networkService.start()
        
        // Surveiller les connexions de pairs
        val connectedPeers = networkService.getConnectedPeers()
        logger.info("DemoModule", "Connected peers: ${connectedPeers.size}")
        
        // Définir quelques capacités
        networkService.setLocalCapability("demo.feature", "enabled")
        
        // Pour tester, vous pourriez même essayer de connecter à un pair fixe
        // networkService.connectToPeer("peer-id-example", "127.0.0.1:9000")
    }
}
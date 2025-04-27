package org.socialmesh.eventbus.events

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.socialmesh.core.model.ResourceIdentifier
import org.socialmesh.core.model.SystemError
import org.socialmesh.eventbus.Event
import kotlin.random.Random

/**
 * Classe de base pour tous les événements avec implémentation commune
 */
@Serializable
abstract class BaseEvent : Event {
    override val id: String = generateEventId()
    abstract override val type: String
    override val timestamp: Instant = Clock.System.now()
    override val source: String = "unknown"
    override val metadata: Map<String, String> = emptyMap()
    
    companion object {
        /**
         * Génère un identifiant unique pour un événement
         */
        private fun generateEventId(): String {
            val randomBytes = Random.nextBytes(8)
            val timestamp = Clock.System.now().toEpochMilliseconds()
            return "evt-${timestamp.toString(16)}-${randomBytes.joinToString("") { "%02x".format(it) }}"
        }
    }
}

/**
 * Événement de cycle de vie du système
 */
@Serializable
sealed class LifecycleEvent : BaseEvent() {
    override val type: String = "system.lifecycle"
    abstract val componentId: String
    abstract val state: String
}

/**
 * Événement de démarrage d'un composant
 */
@Serializable
class ComponentStartedEvent(
    override val source: String,
    override val componentId: String,
    val componentVersion: String,
    override val metadata: Map<String, String> = emptyMap()
) : LifecycleEvent() {
    override val state: String = "started"
}

/**
 * Événement d'arrêt d'un composant
 */
@Serializable
class ComponentStoppedEvent(
    override val source: String,
    override val componentId: String,
    override val metadata: Map<String, String> = emptyMap()
) : LifecycleEvent() {
    override val state: String = "stopped"
}

/**
 * Événement d'erreur système
 */
@Serializable
class ErrorEvent(
    override val source: String,
    val error: SystemError,
    override val metadata: Map<String, String> = emptyMap()
) : BaseEvent() {
    override val type: String = "system.error"
}

/**
 * Événement de configuration modifiée
 */
@Serializable
class ConfigChangedEvent(
    override val source: String,
    val key: String,
    val newValue: String?,
    override val metadata: Map<String, String> = emptyMap()
) : BaseEvent() {
    override val type: String = "system.config"
}

/**
 * Événement de ressource créée
 */
@Serializable
class ResourceCreatedEvent(
    override val source: String,
    val resourceId: ResourceIdentifier,
    val size: Long,
    override val metadata: Map<String, String> = emptyMap()
) : BaseEvent() {
    override val type: String = "system.resource.created"
}

/**
 * Événement de ressource supprimée
 */
@Serializable
class ResourceDeletedEvent(
    override val source: String,
    val resourceId: ResourceIdentifier,
    override val metadata: Map<String, String> = emptyMap()
) : BaseEvent() {
    override val type: String = "system.resource.deleted"
}

/**
 * Événement de module chargé
 */
@Serializable
class ModuleLoadedEvent(
    override val source: String,
    val moduleId: String,
    val version: String,
    override val metadata: Map<String, String> = emptyMap()
) : BaseEvent() {
    override val type: String = "system.module.loaded"
}

/**
 * Événement de module déchargé
 */
@Serializable
class ModuleUnloadedEvent(
    override val source: String,
    val moduleId: String,
    override val metadata: Map<String, String> = emptyMap()
) : BaseEvent() {
    override val type: String = "system.module.unloaded"
}

/**
 * Événement de connexion pair établie
 */
@Serializable
class PeerConnectedEvent(
    override val source: String,
    val peerId: String,
    val address: String,
    override val metadata: Map<String, String> = emptyMap()
) : BaseEvent() {
    override val type: String = "network.peer.connected"
}

/**
 * Événement de déconnexion d'un pair
 */
@Serializable
class PeerDisconnectedEvent(
    override val source: String,
    val peerId: String,
    val reason: String,
    override val metadata: Map<String, String> = emptyMap()
) : BaseEvent() {
    override val type: String = "network.peer.disconnected"
}
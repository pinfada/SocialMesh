package org.socialmesh.eventbus

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant
import org.socialmesh.core.interfaces.SocialMeshComponent
import org.socialmesh.core.model.CryptoIdentity
import kotlin.reflect.KClass

/**
 * Interface principale du bus d'événements
 */
interface EventBus : SocialMeshComponent {
    /**
     * Publie un événement sur le bus
     * @param event L'événement à publier
     * @param priority Priorité de l'événement (0-10, 10 étant la plus élevée)
     * @return L'identifiant unique de l'événement publié
     */
    suspend fun publish(event: Event, priority: Int = 5): String
    
    /**
     * Publie un événement de manière synchrone (non suspendue)
     * À utiliser uniquement pour les événements critiques qui ne peuvent pas attendre
     * @param event L'événement à publier
     * @param priority Priorité de l'événement (0-10, 10 étant la plus élevée)
     * @return L'identifiant unique de l'événement publié
     */
    fun publishSync(event: Event, priority: Int = 5): String
    
    /**
     * S'abonne à un type d'événement spécifique
     * @param eventClass La classe d'événement à laquelle s'abonner
     * @return Un flux d'événements du type spécifié
     */
    fun <T : Event> subscribe(eventClass: KClass<T>): Flow<T>
    
    /**
     * S'abonne à plusieurs types d'événements
     * @param eventClasses Les classes d'événements à laquelle s'abonner
     * @return Un flux d'événements des types spécifiés
     */
    fun subscribeMultiple(vararg eventClasses: KClass<out Event>): Flow<Event>
    
    /**
     * S'abonne à des événements qui correspondent à un filtre spécifique
     * @param filter Fonction de filtrage qui détermine si un événement doit être reçu
     * @return Un flux d'événements qui correspondent au filtre
     */
    fun subscribeWithFilter(filter: (Event) -> Boolean): Flow<Event>
    
    /**
     * Enregistre un abonné aux événements
     * L'abonné recevra les événements via les méthodes annotées avec @Subscribe
     * @param subscriber L'objet abonné
     */
    fun registerSubscriber(subscriber: Any)
    
    /**
     * Désenregistre un abonné
     * @param subscriber L'objet abonné à désenregistrer
     */
    fun unregisterSubscriber(subscriber: Any)
    
    /**
     * Obtient les statistiques du bus d'événements
     * @return Les statistiques actuelles
     */
    fun getStats(): EventBusStats
}

/**
 * Interface de base pour tous les événements du système
 */
interface Event {
    val id: String
    val type: String
    val timestamp: Instant
    val source: String
    val metadata: Map<String, String>
}

/**
 * Statistiques du bus d'événements
 */
data class EventBusStats(
    val totalEventsPublished: Long,
    val activeSubscriptions: Int,
    val queueSize: Int,
    val eventsProcessedPerSecond: Double,
    val averageProcessingTimeMs: Double,
    val lastEventTimestamp: Instant?
)

/**
 * Annotation pour les méthodes d'un abonné qui reçoivent des événements
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Subscribe(
    val priority: Int = 5
)
package org.socialmesh.eventbus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koin.java.KoinJavaComponent.getKoin
import kotlin.reflect.KClass

/**
 * Objet singleton pour faciliter l'accès au bus d'événements
 */
object EventBusProvider {
    private val eventBus by lazy { getKoin().get<EventBus>() }
    
    /**
     * Obtient l'instance du bus d'événements
     */
    fun get(): EventBus = eventBus
}

/**
 * Extensions pour faciliter l'utilisation du bus d'événements
 */

/**
 * Publie un événement sur le bus
 */
suspend fun Event.publish(priority: Int = 5): String {
    return EventBusProvider.get().publish(this, priority)
}

/**
 * Publie un événement de manière synchrone
 */
fun Event.publishSync(priority: Int = 5): String {
    return EventBusProvider.get().publishSync(this, priority)
}

/**
 * S'abonne à un type d'événement spécifique avec une fonction de callback
 */
inline fun <reified T : Event> EventBus.subscribeWithCallback(
    noinline callback: (T) -> Unit
): AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    val flow = this.subscribe(T::class)
    val job = flow.onEach { event ->
        callback(event)
    }.launchIn(scope)
    
    // Retourne un AutoCloseable pour permettre de se désabonner facilement
    return AutoCloseable {
        job.cancel()
    }
}

/**
 * S'abonne à plusieurs types d'événements avec une fonction de callback
 */
fun EventBus.subscribeMultipleWithCallback(
    vararg eventClasses: KClass<out Event>,
    callback: (Event) -> Unit
): AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    val flow = this.subscribeMultiple(*eventClasses)
    val job = flow.onEach { event ->
        callback(event)
    }.launchIn(scope)
    
    return AutoCloseable {
        job.cancel()
    }
}

/**
 * S'abonne à des événements filtrés avec une fonction de callback
 */
fun EventBus.subscribeWithFilterAndCallback(
    filter: (Event) -> Boolean,
    callback: (Event) -> Unit
): AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    val flow = this.subscribeWithFilter(filter)
    val job = flow.onEach { event ->
        callback(event)
    }.launchIn(scope)
    
    return AutoCloseable {
        job.cancel()
    }
}

/**
 * Extension pour créer un abonnement inline avec réification de type
 */
inline fun <reified T : Event> EventBus.subscribe() = subscribe(T::class)

/**
 * Extension pour créer un abonnement à plusieurs types avec réification
 */
inline fun <reified T1 : Event, reified T2 : Event> EventBus.subscribeMultiple() = 
    subscribeMultiple(T1::class, T2::class)

/**
 * Extension pour créer un abonnement à trois types avec réification
 */
inline fun <reified T1 : Event, reified T2 : Event, reified T3 : Event> EventBus.subscribeMultiple() = 
    subscribeMultiple(T1::class, T2::class, T3::class)
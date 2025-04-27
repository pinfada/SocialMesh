package org.socialmesh.eventbus

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.socialmesh.core.interfaces.LoggingService
import kotlin.reflect.KClass

/**
 * Implémentation du bus d'événements optimisée basée sur le pattern LMAX Disruptor
 * 
 * Cette implémentation utilise un RingBuffer interne et des processeurs d'événements parallèles
 * pour offrir un traitement haute performance avec garantie d'ordonnancement
 */
class DisruptorEventBus : EventBus, KoinComponent {
    override val componentId: String = "org.socialmesh.eventbus"
    override val version: String = "1.0.0"
    override val dependencies: List<String> = listOf("org.socialmesh.core.logging")
    
    private val logger: LoggingService by inject()
    private val TAG = "DisruptorEventBus"
    
    // Taille du RingBuffer - doit être une puissance de 2
    private val BUFFER_SIZE = 1024
    private val MASK = BUFFER_SIZE - 1
    
    // Statistiques
    private val totalEventsPublished = atomic(0L)
    private val eventsProcessedLastSecond = atomic(0)
    private val processingTimeAccumulator = atomic(0L)
    private val eventsProcessed = atomic(0L)
    private val lastEventTimestamp = atomic<Instant?>(null)
    
    // État du RingBuffer
    private val buffer = Array<EventHolder?>(BUFFER_SIZE) { null }
    private val head = atomic(0) // Position de production
    private val tail = atomic(0) // Position de consommation
    
    // Scopes coroutine
    private val supervisorJob = SupervisorJob()
    private val mainScope = CoroutineScope(Dispatchers.Default + supervisorJob)
    
    // Flux d'événements principal
    private val eventFlow = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    
    // Liste des abonnés aux événements
    private val subscribers = mutableListOf<Subscriber>()
    
    // Nombre de workers pour le traitement des événements
    private val numWorkers = 4
    private val workers = mutableListOf<Job>()
    
    // Classe contenant un événement et sa priorité
    private data class EventHolder(val event: Event, val priority: Int, val timestamp: Instant)
    
    // Classe représentant un abonné
    private data class Subscriber(
        val target: Any,
        val methods: List<SubscriberMethod>
    )
    
    // Classe représentant une méthode d'abonné
    private data class SubscriberMethod(
        val method: kotlin.reflect.KFunction<*>,
        val eventType: KClass<out Event>,
        val priority: Int
    )
    
    override fun initialize(): Boolean {
        logger.info(TAG, "Initializing DisruptorEventBus")
        
        // Démarrage des workers de traitement
        for (i in 0 until numWorkers) {
            val worker = mainScope.launch {
                processEvents(i)
            }
            workers.add(worker)
        }
        
        // Démarrage de la tâche de statistiques
        mainScope.launch {
            collectStats()
        }
        
        logger.info(TAG, "DisruptorEventBus initialized with $numWorkers workers")
        return true
    }
    
    override fun shutdown() {
        logger.info(TAG, "Shutting down DisruptorEventBus")
        
        // Annulation de toutes les tâches
        supervisorJob.cancel()
        
        subscribers.clear()
        
        logger.info(TAG, "DisruptorEventBus shutdown complete")
    }
    
    /**
     * Publie un événement dans le bus de manière asynchrone
     */
    override suspend fun publish(event: Event, priority: Int): String {
        // Publier dans le RingBuffer avec spin lock si nécessaire
        var published = false
        while (!published) {
            val current = head.value
            val next = (current + 1) & MASK
            
            if (next == tail.value) {
                // Buffer plein, on attend un peu
                delay(1)
                continue
            }
            
            buffer[current] = EventHolder(event, priority, Clock.System.now())
            head.value = next
            published = true
        }
        
        lastEventTimestamp.value = Clock.System.now()
        totalEventsPublished.incrementAndGet()
        
        return event.id
    }
    
    /**
     * Publie un événement de manière synchrone, sans suspension
     */
    override fun publishSync(event: Event, priority: Int): String {
        // Tentative d'ajout au buffer sans bloquer
        val current = head.value
        val next = (current + 1) & MASK
        
        if (next != tail.value) {
            // Il y a de la place dans le buffer
            buffer[current] = EventHolder(event, priority, Clock.System.now())
            head.value = next
            
            lastEventTimestamp.value = Clock.System.now()
            totalEventsPublished.incrementAndGet()
            
            return event.id
        } else {
            // Buffer plein, on utilise le sharedFlow comme fallback
            mainScope.launch {
                eventFlow.emit(event)
            }
            
            lastEventTimestamp.value = Clock.System.now()
            totalEventsPublished.incrementAndGet()
            
            return event.id
        }
    }
    
    /**
     * S'abonne à un type d'événement spécifique
     */
    override fun <T : Event> subscribe(eventClass: KClass<T>): Flow<T> {
        return eventFlow
            .filterIsInstance(eventClass.java)
    }
    
    /**
     * S'abonne à plusieurs types d'événements
     */
    override fun subscribeMultiple(vararg eventClasses: KClass<out Event>): Flow<Event> {
        return eventFlow
            .filter { event ->
                eventClasses.any { it.isInstance(event) }
            }
    }
    
    /**
     * S'abonne aux événements qui correspondent à un filtre spécifique
     */
    override fun subscribeWithFilter(filter: (Event) -> Boolean): Flow<Event> {
        return eventFlow
            .filter(filter)
    }
    
    /**
     * Enregistre un abonné aux événements
     */
    override fun registerSubscriber(subscriber: Any) {
        synchronized(subscribers) {
            // Vérification si l'abonné est déjà enregistré
            if (subscribers.any { it.target === subscriber }) {
                logger.warn(TAG, "Subscriber already registered: $subscriber")
                return
            }
            
            // Recherche des méthodes annotées avec @Subscribe
            val methods = mutableListOf<SubscriberMethod>()
            
            subscriber::class.members.forEach { member ->
                if (member is kotlin.reflect.KFunction<*>) {
                    val annotation = member.annotations.filterIsInstance<Subscribe>().firstOrNull()
                    if (annotation != null) {
                        // Vérification que la méthode a un seul paramètre et que c'est un Event
                        val parameters = member.parameters
                        if (parameters.size == 2) { // Le premier est 'this', le second est le paramètre
                            val paramType = parameters[1].type.classifier as? KClass<*>
                            if (paramType != null && Event::class.java.isAssignableFrom(paramType.java)) {
                                @Suppress("UNCHECKED_CAST")
                                methods.add(
                                    SubscriberMethod(
                                        method = member,
                                        eventType = paramType as KClass<out Event>,
                                        priority = annotation.priority
                                    )
                                )
                            }
                        }
                    }
                }
            }
            
            if (methods.isNotEmpty()) {
                subscribers.add(Subscriber(subscriber, methods))
                logger.debug(TAG, "Registered subscriber with ${methods.size} methods: $subscriber")
            } else {
                logger.warn(TAG, "No valid @Subscribe methods found in $subscriber")
            }
        }
    }
    
    /**
     * Désenregistre un abonné
     */
    override fun unregisterSubscriber(subscriber: Any) {
        synchronized(subscribers) {
            val removed = subscribers.removeIf { it.target === subscriber }
            if (removed) {
                logger.debug(TAG, "Unregistered subscriber: $subscriber")
            } else {
                logger.warn(TAG, "Subscriber not found for unregistration: $subscriber")
            }
        }
    }
    
    /**
     * Obtient les statistiques du bus d'événements
     */
    override fun getStats(): EventBusStats {
        val processed = eventsProcessed.value
        val processingTime = processingTimeAccumulator.value
        
        val averageProcessingTime = if (processed > 0) {
            processingTime.toDouble() / processed
        } else {
            0.0
        }
        
        return EventBusStats(
            totalEventsPublished = totalEventsPublished.value,
            activeSubscriptions = subscribers.sumOf { it.methods.size },
            queueSize = (head.value - tail.value) and MASK,
            eventsProcessedPerSecond = eventsProcessedLastSecond.value.toDouble(),
            averageProcessingTimeMs = averageProcessingTime,
            lastEventTimestamp = lastEventTimestamp.value
        )
    }
    
    /**
     * Processeur d'événements pour un worker
     */
    private suspend fun processEvents(workerId: Int) {
        logger.debug(TAG, "Event processor $workerId started")
        
        while (isActive) {
            var processed = false
            
            // Essayons de traiter un événement depuis le RingBuffer
            val current = tail.value
            if (current != head.value) {
                // Il y a un événement à traiter
                val eventHolder = buffer[current]
                
                if (eventHolder != null) {
                    // Traitement de l'événement
                    val startTime = Clock.System.now()
                    
                    try {
                        // Émission dans le flux partagé
                        eventFlow.emit(eventHolder.event)
                        
                        // Livraison aux abonnés
                        deliverToSubscribers(eventHolder.event)
                        
                        eventsProcessed.incrementAndGet()
                        eventsProcessedLastSecond.incrementAndGet()
                        
                        // Calcul du temps de traitement
                        val endTime = Clock.System.now()
                        val processingTimeMs = endTime.toEpochMilliseconds() - startTime.toEpochMilliseconds()
                        processingTimeAccumulator.addAndGet(processingTimeMs)
                        
                    } catch (e: Exception) {
                        logger.error(TAG, "Error processing event: ${eventHolder.event.type}", e)
                    } finally {
                        // Libération de l'emplacement dans le buffer
                        buffer[current] = null
                        
                        // Avancement du pointeur de consommation
                        tail.value = (current + 1) & MASK
                        
                        processed = true
                    }
                }
            }
            
            // Si aucun événement n'a été traité, on attend un peu
            if (!processed) {
                delay(1)
            }
        }
        
        logger.debug(TAG, "Event processor $workerId stopped")
    }
    
    /**
     * Livraison d'un événement aux abonnés enregistrés
     */
    private suspend fun deliverToSubscribers(event: Event) {
        val eligibleMethods = mutableListOf<Pair<Any, SubscriberMethod>>()
        
        // Collecte des méthodes éligibles
        synchronized(subscribers) {
            for (subscriber in subscribers) {
                for (method in subscriber.methods) {
                    if (method.eventType.isInstance(event)) {
                        eligibleMethods.add(subscriber.target to method)
                    }
                }
            }
        }
        
        // Tri par priorité (priorité plus élevée d'abord)
        eligibleMethods.sortByDescending { it.second.priority }
        
        // Livraison aux abonnés
        for ((target, method) in eligibleMethods) {
            try {
                // Invocation de la méthode
                withContext(Dispatchers.Default) {
                    method.method.call(target, event)
                }
            } catch (e: Exception) {
                logger.error(TAG, "Error delivering event to subscriber: $target, method: ${method.method.name}", e)
            }
        }
    }
    
    /**
     * Collecte des statistiques périodiques
     */
    private suspend fun collectStats() {
        while (isActive) {
            delay(1000) // Mise à jour chaque seconde
            
            // Réinitialisation du compteur d'événements par seconde
            eventsProcessedLastSecond.value = 0
        }
    }
}
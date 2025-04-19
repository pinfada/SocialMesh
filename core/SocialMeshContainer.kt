package org.socialmesh.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import org.socialmesh.core.impl.DefaultLifecycleService
import org.socialmesh.core.impl.InMemoryConfigurationService
import org.socialmesh.core.impl.SimpleLoggingService
import org.socialmesh.core.interfaces.ConfigurationService
import org.socialmesh.core.interfaces.LifecycleService
import org.socialmesh.core.interfaces.LoggingService
import org.socialmesh.core.interfaces.SocialMeshComponent
import org.socialmesh.core.model.SystemError

/**
 * Container principal de l'application SocialMesh
 * Responsable de l'initialisation et de la coordination de tous les composants
 */
class SocialMeshContainer {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val _state = MutableStateFlow<ContainerState>(ContainerState.CREATED)
    val state: StateFlow<ContainerState> = _state
    
    private val _errors = MutableStateFlow<List<SystemError>>(emptyList())
    val errors: StateFlow<List<SystemError>> = _errors
    
    private val registeredComponents = mutableMapOf<String, SocialMeshComponent>()
    private val registeredModules = mutableListOf<Module>()
    
    /**
     * Initialise le container avec les modules Koin de base
     */
    fun initialize() {
        if (_state.value != ContainerState.CREATED) {
            return
        }
        
        _state.value = ContainerState.INITIALIZING
        
        try {
            // Création des modules Koin de base
            val coreModule = module {
                single<LifecycleService> { DefaultLifecycleService() }
                single<ConfigurationService> { InMemoryConfigurationService() }
                single<LoggingService> { SimpleLoggingService() }
                single { this@SocialMeshContainer }
            }
            
            registeredModules.add(coreModule)
            
            // Démarrage de Koin
            startKoin {
                modules(registeredModules)
            }
            
            // Initialisation des services de base
            val services = listOf(
                KoinLocator.getLifecycleService(),
                KoinLocator.getConfigurationService(),
                KoinLocator.getLoggingService()
            )
            
            services.forEach { service ->
                registeredComponents[service.componentId] = service
                if (!service.initialize()) {
                    throw IllegalStateException("Failed to initialize ${service.componentId}")
                }
            }
            
            _state.value = ContainerState.INITIALIZED
        } catch (e: Exception) {
            _state.value = ContainerState.ERROR
            
            val error = SystemError(
                code = 1000,
                message = "Failed to initialize container: ${e.message}",
                severity = org.socialmesh.core.model.ErrorSeverity.CRITICAL,
                category = org.socialmesh.core.model.ErrorCategory.SYSTEM,
                timestamp = Clock.System.now(),
                sourceComponent = "SocialMeshContainer"
            )
            
            _errors.value = _errors.value + error
        }
    }
    
    /**
     * Démarre tous les composants enregistrés
     */
    fun start() {
        if (_state.value != ContainerState.INITIALIZED) {
            return
        }
        
        _state.value = ContainerState.STARTING
        
        try {
            // Démarrage des composants dans l'ordre optimal
            val sortedComponents = registeredComponents.values.sortedWith(
                compareBy<SocialMeshComponent> { it is LifecycleService }.thenBy { it.componentId }
            )
            
            for (component in sortedComponents) {
                if (component !is LifecycleService) {
                    component.initialize()
                }
            }
            
            _state.value = ContainerState.RUNNING
        } catch (e: Exception) {
            _state.value = ContainerState.ERROR
            
            val error = SystemError(
                code = 1001,
                message = "Failed to start container: ${e.message}",
                severity = org.socialmesh.core.model.ErrorSeverity.CRITICAL,
                category = org.socialmesh.core.model.ErrorCategory.SYSTEM,
                timestamp = Clock.System.now(),
                sourceComponent = "SocialMeshContainer"
            )
            
            _errors.value = _errors.value + error
        }
    }
    
    /**
     * Ajoute un module Koin au container
     */
    fun registerModule(module: Module) {
        registeredModules.add(module)
        
        if (_state.value == ContainerState.RUNNING || _state.value == ContainerState.INITIALIZED) {
            // Redémarrer Koin avec le nouveau module
            stopKoin()
            startKoin {
                modules(registeredModules)
            }
        }
    }
    
    /**
     * Enregistre un composant dans le container
     */
    fun registerComponent(component: SocialMeshComponent) {
        registeredComponents[component.componentId] = component
        
        if (_state.value == ContainerState.RUNNING) {
            coroutineScope.launch {
                component.initialize()
            }
        }
    }
    
    /**
     * Récupère un composant enregistré
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : SocialMeshComponent> getComponent(componentId: String): T? {
        return registeredComponents[componentId] as? T
    }
    
    /**
     * Arrête proprement le container et tous ses composants
     */
    fun shutdown() {
        if (_state.value != ContainerState.RUNNING && _state.value != ContainerState.ERROR) {
            return
        }
        
        _state.value = ContainerState.STOPPING
        
        try {
            // Arrêt des composants dans l'ordre inverse
            val reversedComponents = registeredComponents.values.sortedWith(
                compareByDescending<SocialMeshComponent> { it is LifecycleService }.thenBy { it.componentId }
            )
            
            for (component in reversedComponents) {
                component.shutdown()
            }
            
            // Arrêt de Koin
            stopKoin()
            
            _state.value = ContainerState.STOPPED
        } catch (e: Exception) {
            _state.value = ContainerState.ERROR
            
            val error = SystemError(
                code = 1002,
                message = "Failed to shutdown container: ${e.message}",
                severity = org.socialmesh.core.model.ErrorSeverity.HIGH,
                category = org.socialmesh.core.model.ErrorCategory.SYSTEM,
                timestamp = Clock.System.now(),
                sourceComponent = "SocialMeshContainer"
            )
            
            _errors.value = _errors.value + error
        }
    }
}

/**
 * États possibles du container
 */
enum class ContainerState {
    CREATED,
    INITIALIZING,
    INITIALIZED,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    ERROR
}

/**
 * Objet utilitaire pour accéder aux services via Koin
 */
object KoinLocator : KoinComponent {
    fun getLifecycleService(): LifecycleService = inject<LifecycleService>().value
    fun getConfigurationService(): ConfigurationService = inject<ConfigurationService>().value
    fun getLoggingService(): LoggingService = inject<LoggingService>().value
    fun getContainer(): SocialMeshContainer = inject<SocialMeshContainer>().value
}
package org.socialmesh.core.impl

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import org.socialmesh.core.interfaces.*
import kotlin.random.Random

/**
 * Implémentation par défaut du service de cycle de vie
 */
class DefaultLifecycleService : LifecycleService {
    override val componentId: String = "org.socialmesh.core.lifecycle"
    override val version: String = "1.0.0"
    override val dependencies: List<String> = emptyList()
    
    private val shutdownHooks = mutableListOf<() -> Unit>()
    private var _isInitialized = false
    private var _isRunning = false
    private val startTime = Clock.System.now()
    
    override val isInitialized: Boolean
        get() = _isInitialized
    
    override val isRunning: Boolean
        get() = _isRunning
    
    override fun initialize(): Boolean {
        _isInitialized = true
        return true
    }
    
    override fun shutdown() {
        if (_isRunning) {
            _isRunning = false
            // Exécution des hooks de shutdown dans l'ordre inverse
            shutdownHooks.reversed().forEach { it() }
        }
        _isInitialized = false
    }
    
    override fun registerShutdownHook(hook: () -> Unit) {
        shutdownHooks.add(hook)
    }
    
    override fun getSystemInfo(): SystemInfo {
        // Dans une implémentation réelle, ces valeurs seraient obtenues du système
        val deviceId = "sm-" + Random.nextBytes(8).joinToString("") { "%02x".format(it) }
        
        return SystemInfo(
            startTime = startTime,
            deviceId = deviceId,
            appVersion = "0.1.0",
            activeModules = 0,  // À remplacer par le nombre réel
            availableStorage = 1024 * 1024 * 1024,  // 1 GB pour l'exemple
            memoryUsage = 100 * 1024 * 1024  // 100 MB pour l'exemple
        )
    }
}

/**
 * Implémentation en mémoire du service de configuration
 */
class InMemoryConfigurationService : ConfigurationService {
    override val componentId: String = "org.socialmesh.core.config"
    override val version: String = "1.0.0"
    override val dependencies: List<String> = emptyList()
    
    private val configStore = MutableStateFlow<Map<String, Any?>>(emptyMap())
    
    override fun initialize(): Boolean {
        // Charger les configurations par défaut
        set("app.firstRun", true)
        set("app.theme", "system")
        set("network.discovery.enabled", true)
        set("storage.encryptionEnabled", true)
        
        return true
    }
    
    override fun shutdown() {
        // Rien à faire pour cette implémentation en mémoire
    }
    
    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String, defaultValue: T): T {
        val currentConfig = configStore.value
        return (currentConfig[key] as? T) ?: defaultValue
    }
    
    override fun <T> set(key: String, value: T) {
        val currentConfig = configStore.value.toMutableMap()
        currentConfig[key] = value
        configStore.value = currentConfig
    }
    
    override fun delete(key: String) {
        val currentConfig = configStore.value.toMutableMap()
        currentConfig.remove(key)
        configStore.value = currentConfig
    }
    
    override fun observe(key: String): Flow<Any?> {
        return configStore.map { it[key] }
    }
    
    override fun getAll(): Map<String, Any?> {
        return configStore.value
    }
}

/**
 * Implémentation simple du service de logging
 */
class SimpleLoggingService : LoggingService {
    override val componentId: String = "org.socialmesh.core.logging"
    override val version: String = "1.0.0"
    override val dependencies: List<String> = emptyList()
    
    // Utilise un buffer circulaire limité à 1000 entrées pour limiter l'utilisation mémoire
    private val logBuffer = mutableListOf<LogEntry>()
    private val maxBufferSize = 1000
    
    // Flux pour les abonnés
    private val logFlow = MutableSharedFlow<LogEntry>(replay = 100)
    
    override fun initialize(): Boolean {
        info("SimpleLoggingService", "Logging service initialized")
        return true
    }
    
    override fun shutdown() {
        info("SimpleLoggingService", "Logging service shutdown")
    }
    
    override fun debug(tag: String, message: String, throwable: Throwable?) {
        addLogEntry(LogLevel.DEBUG, tag, message, throwable)
    }
    
    override fun info(tag: String, message: String, throwable: Throwable?) {
        addLogEntry(LogLevel.INFO, tag, message, throwable)
    }
    
    override fun warn(tag: String, message: String, throwable: Throwable?) {
        addLogEntry(LogLevel.WARN, tag, message, throwable)
    }
    
    override fun error(tag: String, message: String, throwable: Throwable?) {
        addLogEntry(LogLevel.ERROR, tag, message, throwable)
    }
    
    override fun getLogEntries(): Flow<LogEntry> {
        return logFlow
    }
    
    private fun addLogEntry(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        val entry = LogEntry(
            timestamp = Clock.System.now(),
            level = level,
            tag = tag,
            message = message,
            throwable = throwable
        )
        
        synchronized(logBuffer) {
            if (logBuffer.size >= maxBufferSize) {
                logBuffer.removeAt(0)
            }
            logBuffer.add(entry)
        }
        
        // Émission de l'entrée dans le flux
        logFlow.tryEmit(entry)
        
        // Sortie console pour le développement
        val levelPrefix = when (level) {
            LogLevel.DEBUG -> "D"
            LogLevel.INFO -> "I"
            LogLevel.WARN -> "W"
            LogLevel.ERROR -> "E"
        }
        println("$levelPrefix/$tag: $message")
        throwable?.let { it.printStackTrace() }
    }
}
package org.socialmesh.core.interfaces

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant
import org.socialmesh.core.model.CryptoIdentity
import org.socialmesh.core.model.ResourceIdentifier

/**
 * Interface principale pour tous les composants du système SocialMesh
 */
interface SocialMeshComponent {
    val componentId: String
    val version: String
    val dependencies: List<String>
    fun initialize(): Boolean
    fun shutdown()
}

/**
 * Interface pour les modules dynamiques chargés par le système
 */
interface DynamicModule : SocialMeshComponent {
    val moduleType: ModuleType
    val requiredPermissions: Set<Permission>
    val isActive: Boolean
    fun activate(): Boolean
    fun deactivate(): Boolean
}

/**
 * Types de modules disponibles
 */
enum class ModuleType {
    IDENTITY,
    COMMUNICATION,
    COMMUNITY,
    DISCOVERY,
    VISUALIZATION,
    CUSTOM
}

/**
 * Permissions que les modules peuvent demander
 */
enum class Permission {
    STORAGE_READ,
    STORAGE_WRITE,
    NETWORK_ACCESS,
    IDENTITY_READ,
    IDENTITY_MODIFY,
    CONTACTS_READ,
    CONTACTS_MODIFY,
    EVENTS_SUBSCRIBE,
    EVENTS_PUBLISH
}

/**
 * Service de gestion du cycle de vie de l'application
 */
interface LifecycleService : SocialMeshComponent {
    val isInitialized: Boolean
    val isRunning: Boolean
    fun registerShutdownHook(hook: () -> Unit)
    fun getSystemInfo(): SystemInfo
}

/**
 * Informations système
 */
data class SystemInfo(
    val startTime: Instant,
    val deviceId: String,
    val appVersion: String,
    val activeModules: Int,
    val availableStorage: Long,
    val memoryUsage: Long
)

/**
 * Service de configuration
 */
interface ConfigurationService : SocialMeshComponent {
    fun <T> get(key: String, defaultValue: T): T
    fun <T> set(key: String, value: T)
    fun delete(key: String)
    fun observe(key: String): Flow<Any?>
    fun getAll(): Map<String, Any?>
}

/**
 * Service de logging
 */
interface LoggingService : SocialMeshComponent {
    fun debug(tag: String, message: String, throwable: Throwable? = null)
    fun info(tag: String, message: String, throwable: Throwable? = null)
    fun warn(tag: String, message: String, throwable: Throwable? = null)
    fun error(tag: String, message: String, throwable: Throwable? = null)
    fun getLogEntries(): Flow<LogEntry>
}

/**
 * Entrée de journal
 */
data class LogEntry(
    val timestamp: Instant,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: Throwable?
)

/**
 * Niveau de log
 */
enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

/**
 * Service d'identité cryptographique
 */
interface CryptoService : SocialMeshComponent {
    suspend fun generateIdentity(): CryptoIdentity
    suspend fun importIdentity(keyData: ByteArray, password: String): CryptoIdentity
    suspend fun exportIdentity(identity: CryptoIdentity, password: String): ByteArray
    suspend fun sign(identity: CryptoIdentity, data: ByteArray): ByteArray
    suspend fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean
    suspend fun encrypt(publicKey: ByteArray, data: ByteArray): ByteArray
    suspend fun decrypt(identity: CryptoIdentity, data: ByteArray): ByteArray
}

/**
 * Service de gestion des ressources
 */
interface ResourceService : SocialMeshComponent {
    suspend fun readResource(id: ResourceIdentifier): ByteArray?
    suspend fun writeResource(data: ByteArray): ResourceIdentifier
    suspend fun deleteResource(id: ResourceIdentifier): Boolean
    suspend fun hasResource(id: ResourceIdentifier): Boolean
    suspend fun getResourceSize(id: ResourceIdentifier): Long?
    suspend fun listResources(): List<ResourceIdentifier>
}
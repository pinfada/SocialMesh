package org.socialmesh.storage

import kotlinx.coroutines.flow.Flow
import org.socialmesh.core.interfaces.SocialMeshComponent

/**
 * Interface principale pour le service de stockage
 */
interface StorageService : SocialMeshComponent {
    /**
     * Obtient une collection de stockage
     * @param name Nom de la collection
     * @param options Options de configuration
     * @return L'instance de la collection
     */
    fun getCollection(name: String, options: StorageOptions = StorageOptions()): StorageCollection
    
    /**
     * Vérifie si une collection existe
     * @param name Nom de la collection
     * @return true si la collection existe
     */
    fun hasCollection(name: String): Boolean
    
    /**
     * Supprime une collection
     * @param name Nom de la collection
     * @return true si la suppression a réussi
     */
    fun deleteCollection(name: String): Boolean
    
    /**
     * Liste toutes les collections
     * @return Liste des noms de collections
     */
    fun listCollections(): List<String>
    
    /**
     * Obtient les statistiques de stockage
     * @return Statistiques du service de stockage
     */
    fun getStats(): StorageStats
    
    /**
     * Crée un point de sauvegarde
     * @param name Nom du point de sauvegarde (optionnel)
     * @return Identifiant du point de sauvegarde
     */
    fun createSnapshot(name: String? = null): String
    
    /**
     * Restaure à partir d'un point de sauvegarde
     * @param snapshotId Identifiant du point de sauvegarde
     * @return true si la restauration a réussi
     */
    fun restoreSnapshot(snapshotId: String): Boolean
    
    /**
     * Crée une transaction
     * @return La transaction créée
     */
    fun beginTransaction(): StorageTransaction
}

/**
 * Collection de données stockées
 */
interface StorageCollection {
    /**
     * Nom de la collection
     */
    val name: String
    
    /**
     * Options de configuration
     */
    val options: StorageOptions
    
    /**
     * Récupère une entrée par sa clé
     * @param key Clé de l'entrée
     * @return Valeur associée à la clé ou null si non trouvée
     */
    suspend fun get(key: String): ByteArray?
    
    /**
     * Récupère une entrée par sa clé et la désérialise
     * @param key Clé de l'entrée
     * @param serializer Sérialiseur à utiliser
     * @return Objet désérialisé ou null si non trouvé
     */
    suspend fun <T> getObject(key: String, serializer: StorageSerializer<T>): T?
    
    /**
     * Stocke une entrée
     * @param key Clé de l'entrée
     * @param value Valeur à stocker
     * @return true si l'opération a réussi
     */
    suspend fun put(key: String, value: ByteArray): Boolean
    
    /**
     * Stocke un objet sérialisé
     * @param key Clé de l'entrée
     * @param value Objet à stocker
     * @param serializer Sérialiseur à utiliser
     * @return true si l'opération a réussi
     */
    suspend fun <T> putObject(key: String, value: T, serializer: StorageSerializer<T>): Boolean
    
    /**
     * Supprime une entrée
     * @param key Clé de l'entrée
     * @return true si l'opération a réussi
     */
    suspend fun delete(key: String): Boolean
    
    /**
     * Vérifie si une clé existe
     * @param key Clé à vérifier
     * @return true si la clé existe
     */
    suspend fun has(key: String): Boolean
    
    /**
     * Obtient toutes les clés de la collection
     * @param prefix Préfixe pour filtrer les clés (optionnel)
     * @return Liste des clés correspondantes
     */
    suspend fun keys(prefix: String? = null): List<String>
    
    /**
     * Recherche des entrées avec un préfixe de clé
     * @param prefix Préfixe de clé
     * @param limit Nombre maximal de résultats (optionnel)
     * @return Liste des paires clé-valeur correspondantes
     */
    suspend fun findByPrefix(prefix: String, limit: Int? = null): List<KeyValuePair>
    
    /**
     * Recherche des objets avec un préfixe de clé
     * @param prefix Préfixe de clé
     * @param serializer Sérialiseur à utiliser
     * @param limit Nombre maximal de résultats (optionnel)
     * @return Liste des paires clé-objet correspondantes
     */
    suspend fun <T> findObjectsByPrefix(prefix: String, serializer: StorageSerializer<T>, limit: Int? = null): List<KeyObjectPair<T>>
    
    /**
     * Observe les modifications d'une clé
     * @param key Clé à observer
     * @return Flux de valeurs pour la clé
     */
    fun observe(key: String): Flow<ByteArray?>
    
    /**
     * Observe les modifications d'un objet
     * @param key Clé à observer
     * @param serializer Sérialiseur à utiliser
     * @return Flux d'objets pour la clé
     */
    fun <T> observeObject(key: String, serializer: StorageSerializer<T>): Flow<T?>
    
    /**
     * Observe les modifications pour un préfixe de clé
     * @param prefix Préfixe de clé
     * @return Flux de paires clé-valeur
     */
    fun observePrefix(prefix: String): Flow<KeyValuePair>
    
    /**
     * Supprime toutes les entrées de la collection
     * @return true si l'opération a réussi
     */
    suspend fun clear(): Boolean
    
    /**
     * Obtient le nombre d'entrées dans la collection
     * @param prefix Préfixe pour filtrer le comptage (optionnel)
     * @return Nombre d'entrées
     */
    suspend fun count(prefix: String? = null): Int
    
    /**
     * Ferme la collection
     */
    fun close()
}

/**
 * Transaction de stockage
 */
interface StorageTransaction {
    /**
     * Récupère une entrée par sa clé
     * @param collection Nom de la collection
     * @param key Clé de l'entrée
     * @return Valeur associée à la clé ou null si non trouvée
     */
    suspend fun get(collection: String, key: String): ByteArray?
    
    /**
     * Stocke une entrée
     * @param collection Nom de la collection
     * @param key Clé de l'entrée
     * @param value Valeur à stocker
     */
    suspend fun put(collection: String, key: String, value: ByteArray)
    
    /**
     * Supprime une entrée
     * @param collection Nom de la collection
     * @param key Clé de l'entrée
     */
    suspend fun delete(collection: String, key: String)
    
    /**
     * Confirme la transaction
     * @return true si la transaction a été confirmée avec succès
     */
    suspend fun commit(): Boolean
    
    /**
     * Annule la transaction
     */
    fun rollback()
    
    /**
     * Vérifie si la transaction est active
     * @return true si la transaction est toujours active
     */
    fun isActive(): Boolean
}

/**
 * Options de configuration pour une collection
 */
data class StorageOptions(
    val encrypted: Boolean = false,
    val compressionEnabled: Boolean = true,
    val autoFlush: Boolean = true,
    val ttlSeconds: Int? = null,
    val customOptions: Map<String, String> = emptyMap()
)

/**
 * Statistiques du service de stockage
 */
data class StorageStats(
    val totalCollections: Int,
    val totalKeys: Int,
    val totalSizeBytes: Long,
    val diskSpaceUsageBytes: Long,
    val availableDiskSpaceBytes: Long,
    val activeTransactions: Int,
    val snapshotsCount: Int,
    val compressionRatio: Double,
    val writeOperationsCount: Long,
    val readOperationsCount: Long
)

/**
 * Paire clé-valeur
 */
data class KeyValuePair(
    val key: String,
    val value: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as KeyValuePair

        if (key != other.key) return false
        if (!value.contentEquals(other.value)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + value.contentHashCode()
        return result
    }
}

/**
 * Paire clé-objet
 */
data class KeyObjectPair<T>(
    val key: String,
    val value: T
)

/**
 * Interface pour la sérialisation/désérialisation d'objets
 */
interface StorageSerializer<T> {
    /**
     * Sérialise un objet en tableau d'octets
     * @param obj Objet à sérialiser
     * @return Tableau d'octets
     */
    fun serialize(obj: T): ByteArray
    
    /**
     * Désérialise un tableau d'octets en objet
     * @param bytes Tableau d'octets
     * @return Objet désérialisé
     */
    fun deserialize(bytes: ByteArray): T
}

/**
 * Implémentation du sérialiseur basée sur kotlinx.serialization
 */
class KotlinxSerializer<T>(private val serializer: kotlinx.serialization.KSerializer<T>) : StorageSerializer<T> {
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    override fun serialize(obj: T): ByteArray {
        val jsonString = json.encodeToString(serializer, obj)
        return jsonString.toByteArray(Charsets.UTF_8)
    }
    
    override fun deserialize(bytes: ByteArray): T {
        val jsonString = bytes.toString(Charsets.UTF_8)
        return json.decodeFromString(serializer, jsonString)
    }
}
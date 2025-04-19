package org.socialmesh.core.impl

import com.soywiz.krypto.SHA256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.socialmesh.core.interfaces.ConfigurationService
import org.socialmesh.core.interfaces.LoggingService
import org.socialmesh.core.interfaces.ResourceService
import org.socialmesh.core.model.ResourceIdentifier
import org.socialmesh.core.model.ResourceType
import org.socialmesh.core.platform.FileSystemProvider

/**
 * Service de gestion des ressources utilisant le stockage local
 */
class LocalResourceService : ResourceService, KoinComponent {
    override val componentId: String = "org.socialmesh.core.resource"
    override val version: String = "1.0.0"
    override val dependencies: List<String> = listOf(
        "org.socialmesh.core.config",
        "org.socialmesh.core.logging"
    )
    
    private val configService: ConfigurationService by inject()
    private val loggingService: LoggingService by inject()
    private val TAG = "LocalResourceService"
    
    // Fournisseur d'accès au système de fichiers spécifique à la plateforme
    private lateinit var fileSystem: FileSystemProvider
    
    // Cache des identifiants de ressources
    private val resourceCache = mutableMapOf<ResourceIdentifier, ResourceMetadata>()
    
    override fun initialize(): Boolean {
        fileSystem = FileSystemProvider.create()
        
        if (!fileSystem.initialize()) {
            loggingService.error(TAG, "Failed to initialize file system")
            return false
        }
        
        val resourceDir = configService.get("storage.resourceDir", "resources")
        if (!fileSystem.ensureDirectoryExists(resourceDir)) {
            loggingService.error(TAG, "Failed to create resource directory: $resourceDir")
            return false
        }
        
        // Chargement du cache
        loggingService.info(TAG, "Loading resource cache")
        loadResourceCache()
        
        return true
    }
    
    override fun shutdown() {
        // Sauvegarde des métadonnées du cache
        saveResourceCache()
        fileSystem.close()
    }
    
    /**
     * Lit une ressource à partir de son identifiant
     */
    override suspend fun readResource(id: ResourceIdentifier): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val resourceDir = configService.get("storage.resourceDir", "resources")
            val resourcePath = "$resourceDir/${id.hash}"
            
            if (!fileSystem.fileExists(resourcePath)) {
                loggingService.warn(TAG, "Resource not found: $id")
                return@withContext null
            }
            
            val data = fileSystem.readFile(resourcePath)
            
            // Vérification d'intégrité
            val calculatedHash = SHA256.digest(data).hexString
            if (calculatedHash != id.hash) {
                loggingService.error(TAG, "Resource integrity check failed: $id")
                return@withContext null
            }
            
            return@withContext data
        } catch (e: Exception) {
            loggingService.error(TAG, "Failed to read resource: $id", e)
            return@withContext null
        }
    }
    
    /**
     * Écrit une ressource et retourne son identifiant
     */
    override suspend fun writeResource(data: ByteArray): ResourceIdentifier = withContext(Dispatchers.IO) {
        try {
            // Calcul du hash SHA-256 pour l'identifiant
            val hash = SHA256.digest(data).hexString
            val resourceId = ResourceIdentifier(hash, ResourceType.DATA)
            
            // Vérifier si la ressource existe déjà
            val resourceDir = configService.get("storage.resourceDir", "resources")
            val resourcePath = "$resourceDir/${resourceId.hash}"
            
            if (fileSystem.fileExists(resourcePath)) {
                loggingService.debug(TAG, "Resource already exists: $resourceId")
                return@withContext resourceId
            }
            
            // Écriture du fichier
            fileSystem.writeFile(resourcePath, data)
            
            // Mise à jour du cache
            val metadata = ResourceMetadata(
                size = data.size.toLong(),
                created = kotlinx.datetime.Clock.System.now(),
                type = ResourceType.DATA
            )
            resourceCache[resourceId] = metadata
            
            loggingService.debug(TAG, "Resource written: $resourceId, size: ${data.size} bytes")
            return@withContext resourceId
        } catch (e: Exception) {
            loggingService.error(TAG, "Failed to write resource", e)
            throw e
        }
    }
    
    /**
     * Supprime une ressource
     */
    override suspend fun deleteResource(id: ResourceIdentifier): Boolean = withContext(Dispatchers.IO) {
        try {
            val resourceDir = configService.get("storage.resourceDir", "resources")
            val resourcePath = "$resourceDir/${id.hash}"
            
            if (!fileSystem.fileExists(resourcePath)) {
                loggingService.warn(TAG, "Resource not found for deletion: $id")
                return@withContext false
            }
            
            val result = fileSystem.deleteFile(resourcePath)
            if (result) {
                resourceCache.remove(id)
                loggingService.debug(TAG, "Resource deleted: $id")
            } else {
                loggingService.error(TAG, "Failed to delete resource: $id")
            }
            
            return@withContext result
        } catch (e: Exception) {
            loggingService.error(TAG, "Error deleting resource: $id", e)
            return@withContext false
        }
    }
    
    /**
     * Vérifie si une ressource existe
     */
    override suspend fun hasResource(id: ResourceIdentifier): Boolean = withContext(Dispatchers.IO) {
        // Vérification dans le cache d'abord
        if (resourceCache.containsKey(id)) {
            return@withContext true
        }
        
        // Sinon, vérification dans le système de fichiers
        val resourceDir = configService.get("storage.resourceDir", "resources")
        val resourcePath = "$resourceDir/${id.hash}"
        return@withContext fileSystem.fileExists(resourcePath)
    }
    
    /**
     * Récupère la taille d'une ressource
     */
    override suspend fun getResourceSize(id: ResourceIdentifier): Long? = withContext(Dispatchers.IO) {
        // Vérification dans le cache d'abord
        val cachedMetadata = resourceCache[id]
        if (cachedMetadata != null) {
            return@withContext cachedMetadata.size
        }
        
        // Sinon, vérification dans le système de fichiers
        val resourceDir = configService.get("storage.resourceDir", "resources")
        val resourcePath = "$resourceDir/${id.hash}"
        
        if (!fileSystem.fileExists(resourcePath)) {
            return@withContext null
        }
        
        return@withContext fileSystem.getFileSize(resourcePath)
    }
    
    /**
     * Liste toutes les ressources disponibles
     */
    override suspend fun listResources(): List<ResourceIdentifier> = withContext(Dispatchers.IO) {
        return@withContext resourceCache.keys.toList()
    }
    
    /**
     * Charge le cache des ressources à partir du stockage
     */
    private fun loadResourceCache() {
        try {
            val cacheFile = configService.get("storage.resourceCacheFile", "resource_cache.json")
            
            if (fileSystem.fileExists(cacheFile)) {
                val cacheData = fileSystem.readFile(cacheFile)
                val cacheStr = cacheData.toString(Charsets.UTF_8)
                
                // Désérialisation du cache
                val cacheMap = kotlinx.serialization.json.Json.decodeFromString<Map<String, ResourceMetadata>>(cacheStr)
                
                // Conversion des clés en ResourceIdentifier
                cacheMap.forEach { (key, metadata) ->
                    val id = ResourceIdentifier.fromString(key)
                    if (id != null) {
                        resourceCache[id] = metadata
                    }
                }
                
                loggingService.info(TAG, "Loaded resource cache with ${resourceCache.size} entries")
            } else {
                // Création initiale du cache en scannant le répertoire
                val resourceDir = configService.get("storage.resourceDir", "resources")
                val files = fileSystem.listFiles(resourceDir)
                
                for (file in files) {
                    val hash = file.substringAfterLast('/')
                    val id = ResourceIdentifier(hash, ResourceType.DATA)
                    val size = fileSystem.getFileSize(file) ?: 0L
                    
                    resourceCache[id] = ResourceMetadata(
                        size = size,
                        created = kotlinx.datetime.Clock.System.now(),
                        type = ResourceType.DATA
                    )
                }
                
                loggingService.info(TAG, "Created new resource cache with ${resourceCache.size} entries")
                
                // Sauvegarde du cache initial
                saveResourceCache()
            }
        } catch (e: Exception) {
            loggingService.error(TAG, "Failed to load resource cache", e)
            // Continuer avec un cache vide
            resourceCache.clear()
        }
    }
    
    /**
     * Sauvegarde le cache des ressources
     */
    private fun saveResourceCache() {
        try {
            val cacheFile = configService.get("storage.resourceCacheFile", "resource_cache.json")
            
            // Conversion des ResourceIdentifier en chaînes
            val cacheMap = resourceCache.map { (id, metadata) ->
                id.toString() to metadata
            }.toMap()
            
            // Sérialisation du cache
            val cacheStr = kotlinx.serialization.json.Json.encodeToString(cacheMap)
            val cacheData = cacheStr.toByteArray(Charsets.UTF_8)
            
            fileSystem.writeFile(cacheFile, cacheData)
            loggingService.debug(TAG, "Saved resource cache with ${resourceCache.size} entries")
        } catch (e: Exception) {
            loggingService.error(TAG, "Failed to save resource cache", e)
        }
    }
    
    /**
     * Métadonnées d'une ressource
     */
    @kotlinx.serialization.Serializable
    private data class ResourceMetadata(
        val size: Long,
        val created: kotlinx.datetime.Instant,
        val type: ResourceType
    )
}

/**
 * Extension pour obtenir la représentation hexadécimale d'un tableau de bytes
 */
private val ByteArray.hexString: String
    get() = joinToString("") { byte -> "%02x".format(byte) }
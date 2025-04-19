package org.socialmesh.storage.impl

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.rocksdb.*
import org.socialmesh.core.interfaces.LoggingService
import org.socialmesh.core.platform.FileSystemProvider
import org.socialmesh.eventbus.EventBusProvider
import org.socialmesh.eventbus.events.ErrorEvent
import org.socialmesh.storage.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Implémentation du service de stockage utilisant RocksDB
 */
class RocksDBStorageService : StorageService, KoinComponent {
    override val componentId: String = "org.socialmesh.storage.rocksdb"
    override val version: String = "1.0.0"
    override val dependencies: List<String> = listOf(
        "org.socialmesh.core.logging"
    )
    
    private val logger: LoggingService by inject()
    private val TAG = "RocksDBStorage"
    
    // Espace de noms pour les collections
    private val COLLECTIONS_NAMESPACE = "collections:"
    private val METADATA_NAMESPACE = "metadata:"
    
    // Options RocksDB
    private lateinit var dbOptions: DBOptions
    private lateinit var columnFamilyOptions: ColumnFamilyOptions
    private lateinit var writeOptions: WriteOptions
    private lateinit var readOptions: ReadOptions
    
    // Base de données principale
    private lateinit var db: RocksDB
    
    // Répertoire de stockage
    private lateinit var storageDir: String
    
    // Collections ouvertes
    private val openCollections = ConcurrentHashMap<String, RocksDBStorageCollection>()
    
    // Transactions actives
    private val activeTransactions = ConcurrentHashMap<String, RocksDBStorageTransaction>()
    
    // Statistiques d'utilisation
    private val writeOpsCount = AtomicLong(0)
    private val readOpsCount = AtomicLong(0)
    
    // Points de sauvegarde
    private val snapshots = ConcurrentHashMap<String, Snapshot>()
    
    // Observateurs de modifications
    private val updateSubjects = ConcurrentHashMap<String, MutableSharedFlow<KeyValuePair>>()
    
    // Scope coroutine
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // FileSystem
    private val fileSystem: FileSystemProvider = FileSystemProvider.create()
    
    init {
        // Initialisation de la bibliothèque RocksDB
        RocksDB.loadLibrary()
    }
    
    override fun initialize(): Boolean {
        try {
            logger.info(TAG, "Initializing RocksDB storage service")
            
            // Création du répertoire de stockage
            storageDir = "${fileSystem.getAppDirectory()}/rocksdb"
            if (!fileSystem.ensureDirectoryExists(storageDir)) {
                throw IllegalStateException("Failed to create storage directory: $storageDir")
            }
            
            // Configuration des options RocksDB
            dbOptions = DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true)
                .setIncreaseParallelism(max(1, Runtime.getRuntime().availableProcessors()))
                .setMaxBackgroundJobs(2)
                .setMaxBackgroundFlushes(1)
                .setKeepLogFileNum(5)
                .setMaxTotalWalSize(64 * 1024 * 1024) // 64 MB
                .setMaxOpenFiles(100)
                .setStatsDumpPeriodSec(600) // 10 minutes
                .setStatistics(Statistics())
            
            columnFamilyOptions = ColumnFamilyOptions()
                .setLevelCompactionDynamicLevelBytes(true)
                .setCompressionType(CompressionType.LZ4_COMPRESSION)
                .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)
                .setCompactionStyle(CompactionStyle.LEVEL)
                .setTargetFileSizeBase(64 * 1024 * 1024) // 64 MB
                .setMaxBytesForLevelBase(512 * 1024 * 1024) // 512 MB
                .setWriteBufferSize(64 * 1024 * 1024) // 64 MB
                .setMinWriteBufferNumberToMerge(2)
                .setMaxWriteBufferNumber(6)
                .setNumLevels(4)
                .setTableFormatConfig(
                    BlockBasedTableConfig()
                        .setBlockSize(16 * 1024) // 16 KB
                        .setBlockCache(Cache.newLRUCache(128 * 1024 * 1024)) // 128 MB cache
                        .setCacheIndexAndFilterBlocks(true)
                        .setPinL0FilterAndIndexBlocksInCache(true)
                        .setFormatVersion(5)
                        .setFilterPolicy(BloomFilter(10.0, false))
                )
            
            writeOptions = WriteOptions()
                .setSync(false)
                .setDisableWAL(false)
            
            readOptions = ReadOptions()
                .setVerifyChecksums(true)
                .setFillCache(true)
            
            // Liste des familles de colonnes à ouvrir
            val cfDescriptors = listOf(
                ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
                ColumnFamilyDescriptor("metadata".toByteArray())
            )
            
            val cfHandles = ArrayList<ColumnFamilyHandle>()
            
            // Ouverture de la base de données
            db = RocksDB.open(dbOptions, storageDir, cfDescriptors, cfHandles)
            
            // Démarrage de la tâche de gestion des collections
            coroutineScope.launch {
                maintainCollections()
            }
            
            logger.info(TAG, "RocksDB storage service initialized successfully")
            return true
        } catch (e: Exception) {
            logger.error(TAG, "Failed to initialize RocksDB storage service", e)
            
            // Émission d'un événement d'erreur
            coroutineScope.launch {
                val errorEvent = ErrorEvent(
                    source = componentId,
                    error = org.socialmesh.core.model.SystemError(
                        code = 2000,
                        message = "Failed to initialize RocksDB storage: ${e.message}",
                        severity = org.socialmesh.core.model.ErrorSeverity.CRITICAL,
                        category = org.socialmesh.core.model.ErrorCategory.STORAGE,
                        timestamp = kotlinx.datetime.Clock.System.now(),
                        sourceComponent = componentId
                    )
                )
                EventBusProvider.get().publish(errorEvent)
            }
            
            return false
        }
    }
    
    override fun shutdown() {
        try {
            logger.info(TAG, "Shutting down RocksDB storage service")
            
            // Fermeture des collections ouvertes
            openCollections.values.forEach { it.close() }
            openCollections.clear()
            
            // Annulation des transactions actives
            activeTransactions.values.forEach { it.rollback() }
            activeTransactions.clear()
            
            // Libération des snapshots
            snapshots.values.forEach { it.close() }
            snapshots.clear()
            
            // Fermeture des options
            writeOptions.close()
            readOptions.close()
            columnFamilyOptions.close()
            
            // Fermeture de la base de données
            db.close()
            dbOptions.close()
            
            // Annulation des coroutines
            coroutineScope.cancel()
            
            logger.info(TAG, "RocksDB storage service shutdown complete")
        } catch (e: Exception) {
            logger.error(TAG, "Error during RocksDB storage service shutdown", e)
        }
    }
    
    override fun getCollection(name: String, options: StorageOptions): StorageCollection {
        return openCollections.computeIfAbsent(name) {
            logger.debug(TAG, "Opening collection: $name")
            
            try {
                // Vérifier si la collection existe déjà
                val exists = collectionExists(name)
                
                if (!exists) {
                    // Créer les métadonnées de la collection
                    val metadataKey = "${METADATA_NAMESPACE}$name"
                    val metadataValue = kotlinx.serialization.json.Json.encodeToString(
                        StorageOptions.serializer(),
                        options
                    )
                    
                    db.put(metadataKey.toByteArray(), metadataValue.toByteArray())
                    logger.debug(TAG, "Created new collection: $name")
                }
                
                // Créer et retourner l'objet collection
                RocksDBStorageCollection(name, options, this)
            } catch (e: Exception) {
                logger.error(TAG, "Failed to open collection: $name", e)
                throw e
            }
        }
    }
    
    override fun hasCollection(name: String): Boolean {
        return openCollections.containsKey(name) || collectionExists(name)
    }
    
    override fun deleteCollection(name: String): Boolean {
        try {
            logger.debug(TAG, "Deleting collection: $name")
            
            // Fermer la collection si elle est ouverte
            val collection = openCollections.remove(name)
            collection?.close()
            
            // Supprimer les données de la collection
            val prefix = "${COLLECTIONS_NAMESPACE}$name:"
            val keysToDelete = mutableListOf<ByteArray>()
            
            // Collecter toutes les clés à supprimer
            db.newIterator().use { it ->
                it.seek(prefix.toByteArray())
                while (it.isValid && String(it.key()).startsWith(prefix)) {
                    keysToDelete.add(it.key())
                    it.next()
                }
            }
            
            // Supprimer les clés dans une transaction
            val batch = WriteBatch()
            try {
                keysToDelete.forEach { key ->
                    batch.delete(key)
                }
                
                // Supprimer aussi les métadonnées
                batch.delete("${METADATA_NAMESPACE}$name".toByteArray())
                
                db.write(writeOptions, batch)
                
                logger.info(TAG, "Deleted collection: $name (${keysToDelete.size} keys)")
                return true
            } finally {
                batch.close()
            }
        } catch (e: Exception) {
            logger.error(TAG, "Failed to delete collection: $name", e)
            return false
        }
    }
    
    override fun listCollections(): List<String> {
        val collections = mutableListOf<String>()
        
        try {
            db.newIterator().use { it ->
                val prefix = METADATA_NAMESPACE
                it.seek(prefix.toByteArray())
                
                while (it.isValid && String(it.key()).startsWith(prefix)) {
                    val key = String(it.key())
                    val name = key.substring(prefix.length)
                    collections.add(name)
                    it.next()
                }
            }
        } catch (e: Exception) {
            logger.error(TAG, "Failed to list collections", e)
        }
        
        return collections
    }
    
    override fun getStats(): StorageStats {
        // Calcul du nombre total de clés
        var totalKeys = 0
        var totalSizeBytes = 0L
        
        try {
            for (collection in listCollections()) {
                db.newIterator().use { it ->
                    val prefix = "${COLLECTIONS_NAMESPACE}$collection:"
                    it.seek(prefix.toByteArray())
                    
                    while (it.isValid && String(it.key()).startsWith(prefix)) {
                        totalKeys++
                        totalSizeBytes += it.value().size
                        it.next()
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(TAG, "Error calculating storage stats", e)
        }
        
        // Statistiques du système de fichiers
        val storageFile = File(storageDir)
        val diskSpaceUsage = if (storageFile.exists()) calculateDirSize(storageFile) else 0L
        val availableDiskSpace = fileSystem.getAvailableSpace()
        
        // Statistiques de compression
        val compressionRatio = if (totalSizeBytes > 0) {
            diskSpaceUsage.toDouble() / totalSizeBytes.toDouble()
        } else {
            1.0
        }
        
        return StorageStats(
            totalCollections = listCollections().size,
            totalKeys = totalKeys,
            totalSizeBytes = totalSizeBytes,
            diskSpaceUsageBytes = diskSpaceUsage,
            availableDiskSpaceBytes = availableDiskSpace,
            activeTransactions = activeTransactions.size,
            snapshotsCount = snapshots.size,
            compressionRatio = compressionRatio,
            writeOperationsCount = writeOpsCount.get(),
            readOperationsCount = readOpsCount.get()
        )
    }
    
    override fun createSnapshot(name: String?): String {
        val snapshot = db.getSnapshot()
        val snapshotId = name ?: "snapshot-${System.currentTimeMillis()}-${snapshots.size}"
        
        snapshots[snapshotId] = snapshot
        logger.debug(TAG, "Created snapshot: $snapshotId")
        
        return snapshotId
    }
    
    override fun restoreSnapshot(snapshotId: String): Boolean {
        val snapshot = snapshots[snapshotId] ?: return false
        
        try {
            // Pour RocksDB, une restauration directe n'est pas possible
            // Nous devons donc utiliser le snapshot pour créer une copie
            
            // Créer un répertoire temporaire pour la copie
            val backupDir = "${storageDir}_backup_${System.currentTimeMillis()}"
            if (!fileSystem.ensureDirectoryExists(backupDir)) {
                logger.error(TAG, "Failed to create backup directory: $backupDir")
                return false
            }
            
            // Créer des options de lecture spécifiques au snapshot
            val snapshotReadOptions = ReadOptions()
                .setSnapshot(snapshot)
                .setFillCache(false)
                .setVerifyChecksums(true)
            
            // Effectuer la sauvegarde avec le Checkpoint
            val checkpoint = Checkpoint.create(db)
            checkpoint.createCheckpoint(backupDir)
            
            // Fermer le service actuel
            shutdown()
            
            // Remplacer le répertoire de stockage par la sauvegarde
            val originalDir = File(storageDir)
            val backupDirFile = File(backupDir)
            
            if (originalDir.exists()) {
                originalDir.deleteRecursively()
            }
            
            backupDirFile.renameTo(originalDir)
            
            // Réinitialiser le service
            return initialize()
        } catch (e: Exception) {
            logger.error(TAG, "Failed to restore snapshot: $snapshotId", e)
            return false
        } finally {
            // Fermer les options de lecture spécifiques au snapshot
            snapshots.remove(snapshotId)?.close()
        }
    }
    
    override fun beginTransaction(): StorageTransaction {
        val transaction = RocksDBStorageTransaction(this)
        activeTransactions[transaction.id] = transaction
        
        logger.debug(TAG, "Started transaction: ${transaction.id}")
        return transaction
    }
    
    /**
     * Vérifie si une collection existe
     */
    private fun collectionExists(name: String): Boolean {
        val metadataKey = "${METADATA_NAMESPACE}$name"
        
        try {
            val value = db.get(metadataKey.toByteArray())
            return value != null
        } catch (e: Exception) {
            logger.error(TAG, "Error checking if collection exists: $name", e)
            return false
        }
    }
    
    /**
     * Calcule la taille d'un répertoire récursivement
     */
    private fun calculateDirSize(dir: File): Long {
        var size = 0L
        
        if (dir.isFile) {
            return dir.length()
        } else {
            dir.listFiles()?.forEach { file ->
                size += if (file.isFile) {
                    file.length()
                } else {
                    calculateDirSize(file)
                }
            }
        }
        
        return size
    }
    
    /**
     * Tâche périodique de maintenance des collections
     */
    private suspend fun maintainCollections() {
        while (isActive) {
            try {
                // Vérification des collections avec TTL
                for (collection in openCollections.values) {
                    if (collection.options.ttlSeconds != null) {
                        pruneExpiredEntries(collection)
                    }
                }
                
                // Compaction manuelle si nécessaire
                val stats = getStats()
                if (stats.diskSpaceUsageBytes > 1024 * 1024 * 1024) { // 1 GB
                    db.compactRange()
                }
                
            } catch (e: Exception) {
                logger.error(TAG, "Error during collection maintenance", e)
            }
            
            delay(60_000) // Exécution toutes les minutes
        }
    }
    
    /**
     * Supprime les entrées expirées d'une collection avec TTL
     */
    private fun pruneExpiredEntries(collection: RocksDBStorageCollection) {
        val ttlSeconds = collection.options.ttlSeconds ?: return
        val expirationThreshold = System.currentTimeMillis() - (ttlSeconds * 1000L)
        
        try {
            val keysToDelete = mutableListOf<ByteArray>()
            val prefix = "${COLLECTIONS_NAMESPACE}${collection.name}:"
            
            // Collecter les clés expirées
            db.newIterator().use { it ->
                it.seek(prefix.toByteArray())
                
                while (it.isValid && String(it.key()).startsWith(prefix)) {
                    // Format des données avec timestamp: [valeur]|[timestamp]
                    val valueStr = String(it.value())
                    val parts = valueStr.split("|")
                    
                    if (parts.size == 2) {
                        val timestamp = parts[1].toLongOrNull()
                        if (timestamp != null && timestamp < expirationThreshold) {
                            keysToDelete.add(it.key())
                        }
                    }
                    
                    it.next()
                }
            }
            
            // Supprimer les clés expirées
            if (keysToDelete.isNotEmpty()) {
                val batch = WriteBatch()
                try {
                    keysToDelete.forEach { key ->
                        batch.delete(key)
                    }
                    
                    db.write(writeOptions, batch)
                    logger.debug(TAG, "Pruned ${keysToDelete.size} expired entries from ${collection.name}")
                } finally {
                    batch.close()
                }
            }
        } catch (e: Exception) {
            logger.error(TAG, "Error pruning expired entries from ${collection.name}", e)
        }
    }
    
    /**
     * Méthodes internes pour l'accès à la base de données
     */
    
    internal fun getRocksDB(): RocksDB = db
    
    internal fun getWriteOptions(): WriteOptions = writeOptions
    
    internal fun getReadOptions(): ReadOptions = readOptions
    
    internal fun incrementReadCount() {
        readOpsCount.incrementAndGet()
    }
    
    internal fun incrementWriteCount() {
        writeOpsCount.incrementAndGet()
    }
    
    internal fun removeTransaction(id: String) {
        activeTransactions.remove(id)
    }
    
    internal fun notifyUpdate(collection: String, key: String, value: ByteArray) {
        val fullKey = "${COLLECTIONS_NAMESPACE}$collection:$key"
        val keyValuePair = KeyValuePair(key, value)
        
        // Notifier l'observateur spécifique à la clé
        updateSubjects[fullKey]?.tryEmit(keyValuePair)
        
        // Notifier les observateurs de préfixe
        updateSubjects.keys
            .filter { it.endsWith("*") && fullKey.startsWith(it.substring(0, it.length - 1)) }
            .forEach { updateSubjects[it]?.tryEmit(keyValuePair) }
    }
    
    internal fun getObservableFlow(key: String): Flow<KeyValuePair> {
        return updateSubjects.getOrPut(key) {
            MutableSharedFlow(replay = 1, extraBufferCapacity = 10)
        }
    }
}

/**
 * Implémentation d'une collection de stockage pour RocksDB
 */
class RocksDBStorageCollection(
    override val name: String,
    override val options: StorageOptions,
    private val service: RocksDBStorageService
) : StorageCollection {
    
    private val db = service.getRocksDB()
    private val writeOptions = service.getWriteOptions()
    private val readOptions = service.getReadOptions()
    private val logger: LoggingService by inject()
    private val TAG = "RocksDBCollection"
    
    // Préfixe pour les clés de cette collection
    private val keyPrefix = "collections:$name:"
    
    override suspend fun get(key: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val fullKey = "$keyPrefix$key"
            service.incrementReadCount()
            
            val result = db.get(readOptions, fullKey.toByteArray())
            
            if (result != null && options.ttlSeconds != null) {
                // Format avec TTL: [valeur]|[timestamp]
                val valueStr = String(result)
                val parts = valueStr.split("|")
                
                if (parts.size == 2) {
                    val timestamp = parts[1].toLongOrNull()
                    val expirationThreshold = System.currentTimeMillis() - (options.ttlSeconds * 1000L)
                    
                    if (timestamp != null && timestamp < expirationThreshold) {
                        // La valeur est expirée
                        delete(key)
                        return@withContext null
                    }
                    
                    // Retourner uniquement la valeur, pas le timestamp
                    return@withContext parts[0].toByteArray()
                }
            }
            
            return@withContext result
        } catch (e: Exception) {
            logger.error(TAG, "Error getting key $key from $name", e)
            return@withContext null
        }
    }
    
    override suspend fun <T> getObject(key: String, serializer: StorageSerializer<T>): T? = withContext(Dispatchers.IO) {
        val bytes = get(key) ?: return@withContext null
        
        try {
            return@withContext serializer.deserialize(bytes)
        } catch (e: Exception) {
            logger.error(TAG, "Error deserializing object for key $key from $name", e)
            return@withContext null
        }
    }
    
    override suspend fun put(key: String, value: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val fullKey = "$keyPrefix$key"
            
            val valueToStore = if (options.ttlSeconds != null) {
                // Ajouter un timestamp pour le TTL
                val timestamp = System.currentTimeMillis()
                "${String(value)}|$timestamp".toByteArray()
            } else {
                value
            }
            
            db.put(writeOptions, fullKey.toByteArray(), valueToStore)
            service.incrementWriteCount()
            
            // Notifier les observateurs
            service.notifyUpdate(name, key, value)
            
            return@withContext true
        } catch (e: Exception) {
            logger.error(TAG, "Error putting key $key to $name", e)
            return@withContext false
        }
    }
    
    override suspend fun <T> putObject(key: String, value: T, serializer: StorageSerializer<T>): Boolean = withContext(Dispatchers.IO) {
        try {
            val bytes = serializer.serialize(value)
            return@withContext put(key, bytes)
        } catch (e: Exception) {
            logger.error(TAG, "Error serializing object for key $key to $name", e)
            return@withContext false
        }
    }
    
    override suspend fun delete(key: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val fullKey = "$keyPrefix$key"
            db.delete(writeOptions, fullKey.toByteArray())
            service.incrementWriteCount()
            
            // Notifier les observateurs avec un tableau vide
            service.notifyUpdate(name, key, ByteArray(0))
            
            return@withContext true
        } catch (e: Exception) {
            logger.error(TAG, "Error deleting key $key from $name", e)
            return@withContext false
        }
    }
    
    override suspend fun has(key: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val fullKey = "$keyPrefix$key"
            service.incrementReadCount()
            
            return@withContext db.get(fullKey.toByteArray()) != null
        } catch (e: Exception) {
            logger.error(TAG, "Error checking if key $key exists in $name", e)
            return@withContext false
        }
    }
    
    override suspend fun keys(prefix: String?): List<String> = withContext(Dispatchers.IO) {
        val result = mutableListOf<String>()
        
        try {
            val seekPrefix = if (prefix != null) {
                "$keyPrefix$prefix"
            } else {
                keyPrefix
            }
            
            db.newIterator(readOptions).use { it ->
                it.seek(seekPrefix.toByteArray())
                
                while (it.isValid && String(it.key()).startsWith(seekPrefix)) {
                    val key = String(it.key())
                    // Extraire la clé sans le préfixe
                    val extractedKey = key.substring(keyPrefix.length)
                    result.add(extractedKey)
                    it.next()
                }
            }
            
            service.incrementReadCount()
        } catch (e: Exception) {
            logger.error(TAG, "Error listing keys with prefix $prefix from $name", e)
        }
        
        return@withContext result
    }
    
    override suspend fun findByPrefix(prefix: String, limit: Int?): List<KeyValuePair> = withContext(Dispatchers.IO) {
        val result = mutableListOf<KeyValuePair>()
        
        try {
            val seekPrefix = "$keyPrefix$prefix"
            
            db.newIterator(readOptions).use { it ->
                it.seek(seekPrefix.toByteArray())
                
                var count = 0
                while (it.isValid && String(it.key()).startsWith(seekPrefix) && (limit == null || count < limit)) {
                    val key = String(it.key()).substring(keyPrefix.length)
                    val value = it.value()
                    
                    // Traiter la valeur si TTL est activé
                    val processedValue = if (options.ttlSeconds != null) {
                        val valueStr = String(value)
                        val parts = valueStr.split("|")
                        
                        if (parts.size == 2) {
                            parts[0].toByteArray()
                        } else {
                            value
                        }
                    } else {
                        value
                    }
                    
                    result.add(KeyValuePair(key, processedValue))
                    count++
                    it.next()
                }
            }
            
            service.incrementReadCount()
        } catch (e: Exception) {
            logger.error(TAG, "Error finding entries with prefix $prefix from $name", e)
        }
        
        return@withContext result
    }
    
    override suspend fun <T> findObjectsByPrefix(
        prefix: String,
        serializer: StorageSerializer<T>,
        limit: Int?
    ): List<KeyObjectPair<T>> = withContext(Dispatchers.IO) {
        val keyValuePairs = findByPrefix(prefix, limit)
        
        return@withContext keyValuePairs.mapNotNull { pair ->
            try {
                val obj = serializer.deserialize(pair.value)
                KeyObjectPair(pair.key, obj)
            } catch (e: Exception) {
                logger.error(TAG, "Error deserializing object for key ${pair.key} from $name", e)
                null
            }
        }
    }
    
    override fun observe(key: String): Flow<ByteArray?> {
        val fullKey = "$keyPrefix$key"
        
        return service.getObservableFlow(fullKey)
            .map { it.value }
            .onStart {
                // Émettre la valeur initiale si elle existe
                val initialValue = runBlocking { get(key) }
                emit(initialValue)
            }
    }
    
    override fun <T> observeObject(key: String, serializer: StorageSerializer<T>): Flow<T?> {
        return observe(key)
            .map { bytes ->
                if (bytes != null) {
                    try {
                        serializer.deserialize(bytes)
                    } catch (e: Exception) {
                        logger.error(TAG, "Error deserializing observed object for key $key", e)
                        null
                    }
                } else {
                    null
                }
            }
    }
    
    override fun observePrefix(prefix: String): Flow<KeyValuePair> {
        val fullPrefix = "$keyPrefix$prefix"
        
        return channelFlow {
            val subscription = service.getObservableFlow("$fullPrefix*")
                .collect { pair -> send(pair) }
            
            // Émettre les valeurs initiales
            val initialValues = runBlocking { findByPrefix(prefix) }
            for (pair in initialValues) {
                send(pair)
            }
            
            awaitClose()
        }
    }
    
    override suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        try {
            val keysToDelete = mutableListOf<ByteArray>()
            
            // Collecter toutes les clés de la collection
            db.newIterator(readOptions).use { it ->
                it.seek(keyPrefix.toByteArray())
                
                while (it.isValid && String(it.key()).startsWith(keyPrefix)) {
                    keysToDelete.add(it.key())
                    it.next()
                }
            }
            
            // Supprimer les clés
            if (keysToDelete.isNotEmpty()) {
                val batch = WriteBatch()
                try {
                    keysToDelete.forEach { key ->
                        batch.delete(key)
                    }
                    
                    db.write(writeOptions, batch)
                    service.incrementWriteCount()
                    
                    logger.debug(TAG, "Cleared ${keysToDelete.size} entries from $name")
                    return@withContext true
                } finally {
                    batch.close()
                }
            }
            
            return@withContext true
        } catch (e: Exception) {
            logger.error(TAG, "Error clearing collection $name", e)
            return@withContext false
        }
    }
    
    override suspend fun count(prefix: String?): Int = withContext(Dispatchers.IO) {
        var count = 0
        
        try {
            val seekPrefix = if (prefix != null) {
                "$keyPrefix$prefix"
            } else {
                keyPrefix
            }
            
            db.newIterator(readOptions).use { it ->
                it.seek(seekPrefix.toByteArray())
                
                while (it.isValid && String(it.key()).startsWith(seekPrefix)) {
                    count++
                    it.next()
                }
            }
            
            service.incrementReadCount()
        } catch (e: Exception) {
            logger.error(TAG, "Error counting entries in $name", e)
        }
        
        return@withContext count
    }
    
    override fun close() {
        // Rien à faire, la base de données est gérée par le service
        logger.debug(TAG, "Closed collection: $name")
    }
}

/**
 * Implémentation d'une transaction pour RocksDB
 */
class RocksDBStorageTransaction(
    private val service: RocksDBStorageService
) : StorageTransaction, KoinComponent {
    
    private val logger: LoggingService by inject()
    private val TAG = "RocksDBTransaction"
    
    val id: String = "tx-${System.currentTimeMillis()}-${System.nanoTime()}"
    private val batch = WriteBatch()
    private var isActive = true
    
    // Cache des opérations
    private val operationsCache = mutableListOf<Operation>()
    
    // Classes pour représenter les opérations
    private sealed class Operation
    private data class GetOperation(val collection: String, val key: String) : Operation()
    private data class PutOperation(val collection: String, val key: String, val value: ByteArray) : Operation() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            
            other as PutOperation
            
            if (collection != other.collection) return false
            if (key != other.key) return false
            if (!value.contentEquals(other.value)) return false
            
            return true
        }
        
        override fun hashCode(): Int {
            var result = collection.hashCode()
            result = 31 * result + key.hashCode()
            result = 31 * result + value.contentHashCode()
            return result
        }
    }
    private data class DeleteOperation(val collection: String, val key: String) : Operation()
    
    override suspend fun get(collection: String, key: String): ByteArray? = withContext(Dispatchers.IO) {
        checkActive()
        
        // Vérifier d'abord dans le cache des opérations
        val cachedValue = getCachedValue(collection, key)
        if (cachedValue != null) {
            return@withContext cachedValue
        }
        
        // Sinon, lire depuis la base de données
        val fullKey = "collections:$collection:$key"
        try {
            service.incrementReadCount()
            operationsCache.add(GetOperation(collection, key))
            
            return@withContext service.getRocksDB().get(service.getReadOptions(), fullKey.toByteArray())
        } catch (e: Exception) {
            logger.error(TAG, "Error getting key $key from $collection in transaction", e)
            return@withContext null
        }
    }
    
    override suspend fun put(collection: String, key: String, value: ByteArray) = withContext(Dispatchers.IO) {
        checkActive()
        
        val fullKey = "collections:$collection:$key"
        try {
            batch.put(fullKey.toByteArray(), value)
            operationsCache.add(PutOperation(collection, key, value))
        } catch (e: Exception) {
            logger.error(TAG, "Error putting key $key to $collection in transaction", e)
            throw e
        }
    }
    
    override suspend fun delete(collection: String, key: String) = withContext(Dispatchers.IO) {
        checkActive()
        
        val fullKey = "collections:$collection:$key"
        try {
            batch.delete(fullKey.toByteArray())
            operationsCache.add(DeleteOperation(collection, key))
        } catch (e: Exception) {
            logger.error(TAG, "Error deleting key $key from $collection in transaction", e)
            throw e
        }
    }
    
    override suspend fun commit(): Boolean = withContext(Dispatchers.IO) {
        checkActive()
        
        try {
            service.getRocksDB().write(service.getWriteOptions(), batch)
            service.incrementWriteCount()
            
            // Notifier les observateurs des modifications
            notifyObservers()
            
            isActive = false
            service.removeTransaction(id)
            logger.debug(TAG, "Transaction committed: $id")
            
            return@withContext true
        } catch (e: Exception) {
            logger.error(TAG, "Error committing transaction", e)
            return@withContext false
        } finally {
            batch.close()
        }
    }
    
    override fun rollback() {
        if (!isActive) return
        
        try {
            batch.close()
            isActive = false
            service.removeTransaction(id)
            logger.debug(TAG, "Transaction rolled back: $id")
        } catch (e: Exception) {
            logger.error(TAG, "Error rolling back transaction", e)
        }
    }
    
    override fun isActive(): Boolean = isActive
    
    private fun checkActive() {
        if (!isActive) {
            throw IllegalStateException("Transaction is not active")
        }
    }
    
    private fun getCachedValue(collection: String, key: String): ByteArray? {
        // Parcourir le cache des opérations en ordre inverse
        for (operation in operationsCache.reversed()) {
            when (operation) {
                is PutOperation -> {
                    if (operation.collection == collection && operation.key == key) {
                        return operation.value
                    }
                }
                is DeleteOperation -> {
                    if (operation.collection == collection && operation.key == key) {
                        return null // La clé a été supprimée
                    }
                }
                else -> {}
            }
        }
        
        return null // Pas trouvé dans le cache
    }
    
    private fun notifyObservers() {
        val notifiedKeys = mutableSetOf<Pair<String, String>>()
        
        // Parcourir les opérations pour notifier les observateurs
        for (operation in operationsCache) {
            when (operation) {
                is PutOperation -> {
                    val key = Pair(operation.collection, operation.key)
                    if (!notifiedKeys.contains(key)) {
                        service.notifyUpdate(operation.collection, operation.key, operation.value)
                        notifiedKeys.add(key)
                    }
                }
                is DeleteOperation -> {
                    val key = Pair(operation.collection, operation.key)
                    if (!notifiedKeys.contains(key)) {
                        service.notifyUpdate(operation.collection, operation.key, ByteArray(0))
                        notifiedKeys.add(key)
                    }
                }
                else -> {}
            }
        }
    }
}
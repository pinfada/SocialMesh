
// A terminer
override fun getCollection(name: String, options: StorageOptions): StorageCollection {
        return openCollections.getOrPut(name) {
            // Vérifier si la collection existe déjà
            val exists = hasCollection(name)
            
            if (!exists) {
                // Créer les métadonnées de la collection
                val optionsJson = kotlinx.serialization.json.Json.encodeToString(
                    StorageOptions.serializer(), 
                    options
                )
                
                memScoped {
                    val stmt = prepareStatement(
                        "INSERT INTO metadata (collection_name, options, created_at) VALUES (?, ?, ?)"
                    )
                    
                    sqlite3_bind_text(stmt, 1, name, name.length, SQLITE_TRANSIENT)
                    sqlite3_bind_text(stmt, 2, optionsJson, optionsJson.length, SQLITE_TRANSIENT)
                    sqlite3_bind_int64(stmt, 3, (CFAbsoluteTimeGetCurrent() * 1000).toLong())
                    
                    if (sqlite3_step(stmt) != SQLITE_DONE) {
                        val error = sqlite3_errmsg(dbHandle)?.toKString() ?: "Unknown error"
                        println("Failed to create collection metadata: $error")
                    }
                    
                    sqlite3_finalize(stmt)
                }
            }
            
            SQLiteStorageCollection(name, options, this)
        }
    }
    
    override fun hasCollection(name: String): Boolean {
        memScoped {
            val stmt = prepareStatement(
                "SELECT 1 FROM metadata WHERE collection_name = ? LIMIT 1"
            )
            
            sqlite3_bind_text(stmt, 1, name, name.length, SQLITE_TRANSIENT)
            
            val result = sqlite3_step(stmt)
            val exists = result == SQLITE_ROW
            
            sqlite3_finalize(stmt)
            return exists
        }
    }
    
    override fun deleteCollection(name: String): Boolean {
        // Fermer la collection si elle est ouverte
        openCollections.remove(name)?.close()
        
        memScoped {
            // Commencer une transaction
            executeSql("BEGIN TRANSACTION;")
            
            try {
                // Supprimer les métadonnées
                var stmt = prepareStatement("DELETE FROM metadata WHERE collection_name = ?")
                sqlite3_bind_text(stmt, 1, name, name.length, SQLITE_TRANSIENT)
                
                if (sqlite3_step(stmt) != SQLITE_DONE) {
                    val error = sqlite3_errmsg(dbHandle)?.toKString() ?: "Unknown error"
                    println("Failed to delete collection metadata: $error")
                    executeSql("ROLLBACK;")
                    return false
                }
                
                sqlite3_finalize(stmt)
                
                // Supprimer les données
                stmt = prepareStatement("DELETE FROM collections WHERE collection_name = ?")
                sqlite3_bind_text(stmt, 1, name, name.length, SQLITE_TRANSIENT)
                
                if (sqlite3_step(stmt) != SQLITE_DONE) {
                    val error = sqlite3_errmsg(dbHandle)?.toKString() ?: "Unknown error"
                    println("Failed to delete collection data: $error")
                    executeSql("ROLLBACK;")
                    return false
                }
                
                sqlite3_finalize(stmt)
                
                // Valider la transaction
                executeSql("COMMIT;")
                return true
            } catch (e: Exception) {
                executeSql("ROLLBACK;")
                println("Error deleting collection: ${e.message}")
                return false
            }
        }
    }
    
    override fun listCollections(): List<String> {
        val collections = mutableListOf<String>()
        
        memScoped {
            val stmt = prepareStatement("SELECT collection_name FROM metadata")
            
            while (sqlite3_step(stmt) == SQLITE_ROW) {
                val name = sqlite3_column_text(stmt, 0)?.toKString()
                if (name != null) {
                    collections.add(name)
                }
            }
            
            sqlite3_finalize(stmt)
        }
        
        return collections
    }
    
    override fun getStats(): StorageStats {
        // Calcul du nombre total de clés
        var totalKeys = 0
        var totalSizeBytes = 0L
        
        memScoped {
            // Nombre total de clés
            var stmt = prepareStatement("SELECT COUNT(*) FROM collections")
            if (sqlite3_step(stmt) == SQLITE_ROW) {
                totalKeys = sqlite3_column_int(stmt, 0)
            }
            sqlite3_finalize(stmt)
            
            // Taille totale des données
            stmt = prepareStatement("SELECT SUM(length(value)) FROM collections")
            if (sqlite3_step(stmt) == SQLITE_ROW) {
                totalSizeBytes = sqlite3_column_int64(stmt, 0)
            }
            sqlite3_finalize(stmt)
        }
        
        // Taille du fichier de base de données sur le disque
        val dbSizeBytes = getDatabaseFileSize()
        
        // Espace disque disponible
        val availableDiskSpace = fileSystem.getAvailableSpace()
        
        // Ratio de compression (taille des données / taille du fichier)
        val compressionRatio = if (totalSizeBytes > 0) {
            dbSizeBytes.toDouble() / totalSizeBytes.toDouble()
        } else {
            1.0
        }
        
        return StorageStats(
            totalCollections = listCollections().size,
            totalKeys = totalKeys,
            totalSizeBytes = totalSizeBytes,
            diskSpaceUsageBytes = dbSizeBytes,
            availableDiskSpaceBytes = availableDiskSpace,
            activeTransactions = activeTransactions.size,
            snapshotsCount = 0, // SQLite n'a pas de concept de snapshot comme RocksDB
            compressionRatio = compressionRatio,
            writeOperationsCount = writeOpsCount,
            readOperationsCount = readOpsCount
        )
    }
    
    override fun createSnapshot(name: String?): String {
        // SQLite n'a pas de concept de snapshot comme RocksDB
        // Nous pouvons implémenter une copie de sauvegarde de la base de données
        val snapshotId = name ?: "snapshot-${(CFAbsoluteTimeGetCurrent() * 1000).toLong()}"
        val backupPath = "$storageDir/snapshots/$snapshotId.db"
        
        // Créer le répertoire de snapshot s'il n'existe pas
        fileSystem.ensureDirectoryExists("$storageDir/snapshots")
        
        memScoped {
            // Créer un checkpoint WAL pour s'assurer que toutes les données sont écrites
            executeSql("PRAGMA wal_checkpoint(FULL);")
            
            // Créer une copie de sauvegarde
            val pBackup = sqlite3_backup_init(
                null, // destination (créée en mémoire)
                "main",
                dbHandle,
                "main"
            )
            
            if (pBackup != null) {
                sqlite3_backup_step(pBackup, -1) // -1 = toute la base de données
                sqlite3_backup_finish(pBackup)
                
                // Écrire la base de données en mémoire sur le disque
                executeSql("VACUUM INTO '$backupPath';")
                
                return snapshotId
            } else {
                val error = sqlite3_errmsg(dbHandle)?.toKString() ?: "Unknown error"
                println("Failed to create backup: $error")
                throw IllegalStateException("Failed to create backup: $error")
            }
        }
    }
    
    override fun restoreSnapshot(snapshotId: String): Boolean {
        val backupPath = "$storageDir/snapshots/$snapshotId.db"
        
        // Vérifier si le fichier de sauvegarde existe
        if (!fileSystem.fileExists(backupPath)) {
            println("Snapshot file not found: $backupPath")
            return false
        }
        
        try {
            // Fermer toutes les collections et transactions
            openCollections.values.forEach { it.close() }
            openCollections.clear()
            
            activeTransactions.values.forEach { it.rollback() }
            activeTransactions.clear()
            
            // Fermer la base de données actuelle
            sqlite3_close(dbHandle)
            dbHandle = null
            
            // Remplacer le fichier de base de données actuel par la sauvegarde
            fileSystem.deleteFile(dbPath)
            
            // Copier le fichier de sauvegarde
            val backupData = fileSystem.readFile(backupPath)
            fileSystem.writeFile(dbPath, backupData)
            
            // Réinitialiser la base de données
            return initialize()
        } catch (e: Exception) {
            println("Failed to restore from snapshot: ${e.message}")
            return false
        }
    }
    
    override fun beginTransaction(): StorageTransaction {
        val transaction = SQLiteStorageTransaction(this)
        activeTransactions[transaction.id] = transaction
        
        // Débuter une transaction SQLite
        executeSql("BEGIN TRANSACTION;")
        
        return transaction
    }
    
    /**
     * Méthodes internes pour l'accès à la base de données
     */
    
    internal fun prepareStatement(sql: String): CPointer<sqlite3_stmt> {
        memScoped {
            val ppStmt = alloc<CPointerVar<sqlite3_stmt>>()
            
            val result = sqlite3_prepare_v2(
                dbHandle,
                sql,
                sql.length,
                ppStmt.ptr,
                null
            )
            
            if (result != SQLITE_OK) {
                val error = sqlite3_errmsg(dbHandle)?.toKString() ?: "Unknown error"
                throw IllegalStateException("Failed to prepare statement: $error")
            }
            
            return ppStmt.value!!
        }
    }
    
    internal fun executeSql(sql: String): Boolean {
        memScoped {
            val result = sqlite3_exec(
                dbHandle,
                sql,
                null,
                null,
                null
            )
            
            if (result != SQLITE_OK) {
                val error = sqlite3_errmsg(dbHandle)?.toKString() ?: "Unknown error"
                println("Failed to execute SQL: $error")
                return false
            }
            
            return true
        }
    }
    
    internal fun incrementReadCount() {
        readOpsCount++
    }
    
    internal fun incrementWriteCount() {
        writeOpsCount++
    }
    
    internal fun removeTransaction(id: String) {
        activeTransactions.remove(id)
    }
    
    internal fun notifyUpdate(collection: String, key: String, value: ByteArray) {
        val fullKey = "$collection:$key"
        val keyValuePair = KeyValuePair(key, value)
        
        // Notifier les observateurs spécifiques
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
    
    private fun getDatabaseFileSize(): Long {
        return try {
            fileSystem.getFileSize(dbPath) ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}

/**
 * Implémentation d'une collection de stockage pour SQLite
 */
class SQLiteStorageCollection(
    override val name: String,
    override val options: StorageOptions,
    private val service: SQLiteStorageService
) : StorageCollection {
    
    private val TAG = "SQLiteCollection"
    
    override suspend fun get(key: String): ByteArray? = withContext(Dispatchers.Default) {
        try {
            service.incrementReadCount()
            
            memScoped {
                val stmt = service.prepareStatement(
                    "SELECT value, timestamp FROM collections WHERE collection_name = ? AND key = ? LIMIT 1"
                )
                
                sqlite3_bind_text(stmt, 1, name, name.length, SQLITE_TRANSIENT)
                sqlite3_bind_text(stmt, 2, key, key.length, SQLITE_TRANSIENT)
                
                if (sqlite3_step(stmt) == SQLITE_ROW) {
                    // Récupérer les données et le timestamp
                    val blob = sqlite3_column_blob(stmt, 0)
                    val blobSize = sqlite3_column_bytes(stmt, 0)
                    val timestamp = sqlite3_column_int64(stmt, 1)
                    
                    // Vérifier si la valeur est expirée (si TTL est défini)
                    if (options.ttlSeconds != null) {
                        val currentTime = (CFAbsoluteTimeGetCurrent() * 1000).toLong()
                        val expirationTime = timestamp + (options.ttlSeconds * 1000L)
                        
                        if (currentTime > expirationTime) {
                            // La valeur est expirée, la supprimer
                            sqlite3_finalize(stmt)
                            delete(key)
                            return@withContext null
                        }
                    }
                    
                    // Copier les données du blob
                    val result = ByteArray(blobSize)
                    if (blob != null && blobSize > 0) {
                        memcpy(result.refTo(0), blob, blobSize.toULong())
                    }
                    
                    sqlite3_finalize(stmt)
                    return@withContext result
                }
                
                sqlite3_finalize(stmt)
                return@withContext null
            }
        } catch (e: Exception) {
            println("Error getting key $key from $name: ${e.message}")
            return@withContext null
        }
    }
    
    override suspend fun <T> getObject(key: String, serializer: StorageSerializer<T>): T? = withContext(Dispatchers.Default) {
        val bytes = get(key) ?: return@withContext null
        
        try {
            return@withContext serializer.deserialize(bytes)
        } catch (e: Exception) {
            println("Error deserializing object for key $key from $name: ${e.message}")
            return@withContext null
        }
    }
    
    override suspend fun put(key: String, value: ByteArray): Boolean = withContext(Dispatchers.Default) {
        try {
            service.incrementWriteCount()
            
            memScoped {
                val stmt = service.prepareStatement(
                    "INSERT OR REPLACE INTO collections (collection_name, key, value, timestamp) VALUES (?, ?, ?, ?)"
                )
                
                // Lier les paramètres
                sqlite3_bind_text(stmt, 1, collection, collection.length, SQLITE_TRANSIENT)
                sqlite3_bind_text(stmt, 2, key, key.length, SQLITE_TRANSIENT)
                sqlite3_bind_blob(stmt, 3, value.refTo(0), value.size, SQLITE_TRANSIENT)
                sqlite3_bind_int64(stmt, 4, (CFAbsoluteTimeGetCurrent() * 1000).toLong())
                
                val result = sqlite3_step(stmt)
                sqlite3_finalize(stmt)
                
                if (result != SQLITE_DONE) {
                    val error = sqlite3_errmsg(service.dbHandle)?.toKString() ?: "Unknown error"
                    println("Failed to write value in transaction: $error")
                    throw RuntimeException("Failed to write value: $error")
                }
            }
        } catch (e: Exception) {
            println("Error putting key $key to $collection in transaction: ${e.message}")
            throw e
        }
    }
    
    override suspend fun delete(collection: String, key: String) = withContext(Dispatchers.Default) {
        checkActive()
        
        try {
            service.incrementWriteCount()
            
            memScoped {
                val stmt = service.prepareStatement(
                    "DELETE FROM collections WHERE collection_name = ? AND key = ?"
                )
                
                sqlite3_bind_text(stmt, 1, collection, collection.length, SQLITE_TRANSIENT)
                sqlite3_bind_text(stmt, 2, key, key.length, SQLITE_TRANSIENT)
                
                val result = sqlite3_step(stmt)
                sqlite3_finalize(stmt)
                
                if (result != SQLITE_DONE) {
                    val error = sqlite3_errmsg(service.dbHandle)?.toKString() ?: "Unknown error"
                    println("Failed to delete key in transaction: $error")
                    throw RuntimeException("Failed to delete key: $error")
                }
            }
        } catch (e: Exception) {
            println("Error deleting key $key from $collection in transaction: ${e.message}")
            throw e
        }
    }
    
    override suspend fun commit(): Boolean = withContext(Dispatchers.Default) {
        checkActive()
        
        try {
            val result = service.executeSql("COMMIT;")
            isActive = false
            service.removeTransaction(id)
            return@withContext result
        } catch (e: Exception) {
            println("Error committing transaction: ${e.message}")
            rollback()
            return@withContext false
        }
    }
    
    override fun rollback() {
        if (!isActive) return
        
        try {
            service.executeSql("ROLLBACK;")
            isActive = false
            service.removeTransaction(id)
        } catch (e: Exception) {
            println("Error rolling back transaction: ${e.message}")
        }
    }
    
    override fun isActive(): Boolean = isActive
    
    private fun checkActive() {
        if (!isActive) {
            throw IllegalStateException("Transaction is not active")
        }
    }
} INTO collections (collection_name, key, value, timestamp) VALUES (?, ?, ?, ?)"
                )
                
                // Lier les paramètres
                sqlite3_bind_text(stmt, 1, name, name.length, SQLITE_TRANSIENT)
                sqlite3_bind_text(stmt, 2, key, key.length, SQLITE_TRANSIENT)
                
                // Pour les données binaires, nous devons utiliser BLOB
                val nsData = value.usePinned { pinnedData ->
                    NSData.dataWithBytesNoCopy(
                        pinnedData.addressOf(0),
                        value.size.toULong(),
                        false
                    )
                }
                
                sqlite3_bind_blob(stmt, 3, value.refTo(0), value.size, SQLITE_TRANSIENT)
                sqlite3_bind_int64(stmt, 4, (CFAbsoluteTimeGetCurrent() * 1000).toLong())
                
                val result = sqlite3_step(stmt)
                sqlite3_finalize(stmt)
                
                if (result != SQLITE_DONE) {
                    val error = sqlite3_errmsg(service.dbHandle)?.toKString() ?: "Unknown error"
                    println("Failed to write value: $error")
                    return@withContext false
                }
                
                // Notifier les observateurs
                service.notifyUpdate(name, key, value)
                
                return@withContext true
            }
        } catch (e: Exception) {
            println("Error putting key $key to $name: ${e.message}")
            return@withContext false
        }
    }
    
    override suspend fun <T> putObject(key: String, value: T, serializer: StorageSerializer<T>): Boolean = withContext(Dispatchers.Default) {
        try {
            val bytes = serializer.serialize(value)
            return@withContext put(key, bytes)
        } catch (e: Exception) {
            println("Error serializing object for key $key to $name: ${e.message}")
            return@withContext false
        }
    }
    
    override suspend fun delete(key: String): Boolean = withContext(Dispatchers.Default) {
        try {
            service.incrementWriteCount()
            
            memScoped {
                val stmt = service.prepareStatement(
                    "DELETE FROM collections WHERE collection_name = ? AND key = ?"
                )
                
                sqlite3_bind_text(stmt, 1, name, name.length, SQLITE_TRANSIENT)
                sqlite3_bind_text(stmt, 2, key, key.length, SQLITE_TRANSIENT)
                
                val result = sqlite3_step(stmt)
                sqlite3_finalize(stmt)
                
                if (result != SQLITE_DONE) {
                    val error = sqlite3_errmsg(service.dbHandle)?.toKString() ?: "Unknown error"
                    println("Failed to delete key: $error")
                    return@withContext false
                }
                
                // Notifier les observateurs avec un tableau vide
                service.notifyUpdate(name, key, ByteArray(0))
                
                return@withContext true
            }
        } catch (e: Exception) {
            println("Error deleting key $key from $name: ${e.message}")
            return@withContext false
        }
    }
    
    override suspend fun has(key: String): Boolean = withContext(Dispatchers.Default) {
        try {
            service.incrementReadCount()
            
            memScoped {
                val stmt = service.prepareStatement(
                    "SELECT 1 FROM collections WHERE collection_name = ? AND key = ? LIMIT 1"
                )
                
                sqlite3_bind_text(stmt, 1, name, name.length, SQLITE_TRANSIENT)
                sqlite3_bind_text(stmt, 2, key, key.length, SQLITE_TRANSIENT)
                
                val result = sqlite3_step(stmt)
                val exists = result == SQLITE_ROW
                
                sqlite3_finalize(stmt)
                return@withContext exists
            }
        } catch (e: Exception) {
            println("Error checking if key $key exists in $name: ${e.message}")
            return@withContext false
        }
    }
    
    override suspend fun keys(prefix: String?): List<String> = withContext(Dispatchers.Default) {
        val result = mutableListOf<String>()
        
        try {
            service.incrementReadCount()
            
            memScoped {
                val sql = if (prefix != null) {
                    "SELECT key FROM collections WHERE collection_name = ? AND key LIKE ? ORDER BY key"
                } else {
                    "SELECT key FROM collections WHERE collection_name = ? ORDER BY key"
                }
                
                val stmt = service.prepareStatement(sql)
                
                sqlite3_bind_text(stmt, 1, name, name.length, SQLITE_TRANSIENT)
                
                if (prefix != null) {
                    val prefixPattern = "$prefix%"
                    sqlite3_bind_text(stmt, 2, prefixPattern, prefixPattern.length, SQLITE_TRANSIENT)
                }
                
                while (sqlite3_step(stmt) == SQLITE_ROW) {
                    val key = sqlite3_column_text(stmt, 0)?.toKString()
                    if (key != null) {
                        result.add(key)
                    }
                }
                
                sqlite3_finalize(stmt)
            }
        } catch (e: Exception) {
            println("Error listing keys with prefix $prefix from $name: ${e.message}")
        }
        
        return@withContext result
    }
    
    override suspend fun findByPrefix(prefix: String, limit: Int?): List<KeyValuePair> = withContext(Dispatchers.Default) {
        val result = mutableListOf<KeyValuePair>()
        
        try {
            service.incrementReadCount()
            
            memScoped {
                val sql = if (limit != null) {
                    "SELECT key, value FROM collections WHERE collection_name = ? AND key LIKE ? ORDER BY key LIMIT ?"
                } else {
                    "SELECT key, value FROM collections WHERE collection_name = ? AND key LIKE ? ORDER BY key"
                }
                
                val stmt = service.prepareStatement(sql)
                
                sqlite3_bind_text(stmt, 1, name, name.length, SQLITE_TRANSIENT)
                
                val prefixPattern = "$prefix%"
                sqlite3_bind_text(stmt, 2, prefixPattern, prefixPattern.length, SQLITE_TRANSIENT)
                
                if (limit != null) {
                    sqlite3_bind_int(stmt, 3, limit)
                }
                
                while (sqlite3_step(stmt) == SQLITE_ROW) {
                    val key = sqlite3_column_text(stmt, 0)?.toKString()
                    val blob = sqlite3_column_blob(stmt, 1)
                    val blobSize = sqlite3_column_bytes(stmt, 1)
                    
                    if (key != null && blob != null && blobSize > 0) {
                        val value = ByteArray(blobSize)
                        memcpy(value.refTo(0), blob, blobSize.toULong())
                        
                        result.add(KeyValuePair(key, value))
                    }
                }
                
                sqlite3_finalize(stmt)
            }
        } catch (e: Exception) {
            println("Error finding entries with prefix $prefix from $name: ${e.message}")
        }
        
        return@withContext result
    }
    
    override suspend fun <T> findObjectsByPrefix(
        prefix: String,
        serializer: StorageSerializer<T>,
        limit: Int?
    ): List<KeyObjectPair<T>> = withContext(Dispatchers.Default) {
        val keyValuePairs = findByPrefix(prefix, limit)
        
        return@withContext keyValuePairs.mapNotNull { pair ->
            try {
                val obj = serializer.deserialize(pair.value)
                KeyObjectPair(pair.key, obj)
            } catch (e: Exception) {
                println("Error deserializing object for key ${pair.key} from $name: ${e.message}")
                null
            }
        }
    }
    
    override fun observe(key: String): Flow<ByteArray?> {
        val fullKey = "$name:$key"
        
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
                if (bytes != null && bytes.isNotEmpty()) {
                    try {
                        serializer.deserialize(bytes)
                    } catch (e: Exception) {
                        println("Error deserializing observed object for key $key: ${e.message}")
                        null
                    }
                } else {
                    null
                }
            }
    }
    
    override fun observePrefix(prefix: String): Flow<KeyValuePair> {
        val fullPrefix = "$name:$prefix"
        
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
    
    override suspend fun clear(): Boolean = withContext(Dispatchers.Default) {
        try {
            service.incrementWriteCount()
            
            memScoped {
                val stmt = service.prepareStatement(
                    "DELETE FROM collections WHERE collection_name = ?"
                )
                
                sqlite3_bind_text(stmt, 1, name, name.length, SQLITE_TRANSIENT)
                
                val result = sqlite3_step(stmt)
                sqlite3_finalize(stmt)
                
                if (result != SQLITE_DONE) {
                    val error = sqlite3_errmsg(service.dbHandle)?.toKString() ?: "Unknown error"
                    println("Failed to clear collection: $error")
                    return@withContext false
                }
                
                return@withContext true
            }
        } catch (e: Exception) {
            println("Error clearing collection $name: ${e.message}")
            return@withContext false
        }
    }
    
    override suspend fun count(prefix: String?): Int = withContext(Dispatchers.Default) {
        try {
            service.incrementReadCount()
            
            memScoped {
                val sql = if (prefix != null) {
                    "SELECT COUNT(*) FROM collections WHERE collection_name = ? AND key LIKE ?"
                } else {
                    "SELECT COUNT(*) FROM collections WHERE collection_name = ?"
                }
                
                val stmt = service.prepareStatement(sql)
                
                sqlite3_bind_text(stmt, 1, name, name.length, SQLITE_TRANSIENT)
                
                if (prefix != null) {
                    val prefixPattern = "$prefix%"
                    sqlite3_bind_text(stmt, 2, prefixPattern, prefixPattern.length, SQLITE_TRANSIENT)
                }
                
                if (sqlite3_step(stmt) == SQLITE_ROW) {
                    val count = sqlite3_column_int(stmt, 0)
                    sqlite3_finalize(stmt)
                    return@withContext count
                }
                
                sqlite3_finalize(stmt)
                return@withContext 0
            }
        } catch (e: Exception) {
            println("Error counting entries in $name: ${e.message}")
            return@withContext 0
        }
    }
    
    override fun close() {
        // Rien à faire pour SQLite
    }
}

/**
 * Implémentation d'une transaction pour SQLite
 */
class SQLiteStorageTransaction(
    private val service: SQLiteStorageService
) : StorageTransaction {
    
    val id: String = "tx-${(CFAbsoluteTimeGetCurrent() * 1000).toLong()}-${hashCode()}"
    
    private var isActive: Boolean = true
    
    override suspend fun get(collection: String, key: String): ByteArray? = withContext(Dispatchers.Default) {
        checkActive()
        
        try {
            service.incrementReadCount()
            
            memScoped {
                val stmt = service.prepareStatement(
                    "SELECT value FROM collections WHERE collection_name = ? AND key = ? LIMIT 1"
                )
                
                sqlite3_bind_text(stmt, 1, collection, collection.length, SQLITE_TRANSIENT)
                sqlite3_bind_text(stmt, 2, key, key.length, SQLITE_TRANSIENT)
                
                if (sqlite3_step(stmt) == SQLITE_ROW) {
                    val blob = sqlite3_column_blob(stmt, 0)
                    val blobSize = sqlite3_column_bytes(stmt, 0)
                    
                    val result = ByteArray(blobSize)
                    if (blob != null && blobSize > 0) {
                        memcpy(result.refTo(0), blob, blobSize.toULong())
                    }
                    
                    sqlite3_finalize(stmt)
                    return@withContext result
                }
                
                sqlite3_finalize(stmt)
                return@withContext null
            }
        } catch (e: Exception) {
            println("Error getting key $key from $collection in transaction: ${e.message}")
            return@withContext null
        }
    }
    
    override suspend fun put(collection: String, key: String, value: ByteArray) = withContext(Dispatchers.Default) {
        checkActive()
        
        try {
            service.incrementWriteCount()
            
            memScoped {
                val stmt = service.prepareStatement(
                    "INSERT OR REPLACEpackage org.socialmesh.storage.impl

import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import org.socialmesh.core.interfaces.LoggingService
import org.socialmesh.core.platform.FileSystemProvider
import org.socialmesh.storage.*
import platform.CoreFoundation.CFAbsoluteTimeGetCurrent
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.dataWithBytesNoCopy
import platform.posix.*
import platform.sqlite3.*

/**
 * Implémentation du service de stockage utilisant SQLite pour iOS
 */
class SQLiteStorageService : StorageService {
    override val componentId: String = "org.socialmesh.storage.sqlite"
    override val version: String = "1.0.0"
    override val dependencies: List<String> = listOf("org.socialmesh.core.logging")
    
    private val TAG = "SQLiteStorage"
    
    // Base de données SQLite
    private var dbHandle: CPointer<sqlite3>? = null
    
    // Répertoire de stockage
    private lateinit var storageDir: String
    private lateinit var dbPath: String
    
    // FileSystem
    private val fileSystem: FileSystemProvider = FileSystemProvider.create()
    
    // Collections ouvertes
    private val openCollections = mutableMapOf<String, SQLiteStorageCollection>()
    
    // Transactions actives
    private val activeTransactions = mutableMapOf<String, SQLiteStorageTransaction>()
    
    // Statistiques
    private var writeOpsCount: Long = 0
    private var readOpsCount: Long = 0
    
    // Flux d'événements
    private val updateSubjects = mutableMapOf<String, MutableSharedFlow<KeyValuePair>>()
    
    // Logger
    private lateinit var logger: LoggingService
    
    override fun initialize(): Boolean {
        try {
            // Nous utilisons le système de fichiers pour obtenir le répertoire d'application
            storageDir = fileSystem.getAppDirectory()
            dbPath = "$storageDir/socialmesh.db"
            
            // Initialisation de la base de données SQLite
            memScoped {
                val ppDb = alloc<CPointerVar<sqlite3>>()
                
                val result = sqlite3_open_v2(
                    dbPath, 
                    ppDb.ptr, 
                    SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE,
                    null
                )
                
                if (result != SQLITE_OK) {
                    val errorMsg = sqlite3_errmsg(ppDb.value)?.toKString() ?: "Unknown error"
                    throw IllegalStateException("Failed to open database: $errorMsg")
                }
                
                dbHandle = ppDb.value
                
                // Configuration de SQLite
                executeSql("PRAGMA journal_mode = WAL;") // Write-Ahead Logging pour améliorer les performances
                executeSql("PRAGMA synchronous = NORMAL;") // Synchronisation normale (équilibre entre performance et sécurité)
                executeSql("PRAGMA temp_store = MEMORY;") // Stockage temporaire en mémoire
                executeSql("PRAGMA cache_size = 10000;") // 10000 pages (environ 40MB)
                
                // Création de la table de métadonnées si elle n'existe pas
                executeSql("""
                    CREATE TABLE IF NOT EXISTS metadata (
                        collection_name TEXT PRIMARY KEY,
                        options TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    );
                """.trimIndent())
                
                // Création de la table de données si elle n'existe pas
                executeSql("""
                    CREATE TABLE IF NOT EXISTS collections (
                        collection_name TEXT NOT NULL,
                        key TEXT NOT NULL,
                        value BLOB NOT NULL,
                        timestamp INTEGER NOT NULL,
                        PRIMARY KEY (collection_name, key)
                    );
                """.trimIndent())
                
                // Création d'index pour améliorer les performances
                executeSql("CREATE INDEX IF NOT EXISTS idx_collections_prefix ON collections(collection_name, key);")
                
                return true
            }
        } catch (e: Exception) {
            println("Error initializing SQLite storage: ${e.message}")
            return false
        }
    }
    
    override fun shutdown() {
        try {
            // Fermeture des collections ouvertes
            openCollections.values.forEach { it.close() }
            openCollections.clear()
            
            // Annulation des transactions actives
            activeTransactions.values.forEach { it.rollback() }
            activeTransactions.clear()
            
            // Fermeture de la base de données
            sqlite3_close(dbHandle)
            dbHandle = null
        } catch (e: Exception) {
            println("Error shutting down SQLite storage: ${e.message}")
        }
    }
    
    override fun getCollection(name: String, options: StorageOptions): StorageCollection {
        return openCollections.getOrPut(name) {
            // Vérifier si la collection existe déjà
            val exists = hasCollection(name)
            
            if (!exists) {
                // Créer les métadonnées de la collection
                val optionsJson = kotlinx.serialization.json.Json.encodeToString(
                    StorageOptions.serializer(), 
                    options
                )
                
                memScoped {
                    val stmt = prepareStatement(
                        "INSERT INTO metadata (collection_name, options, created_at) VALUES (?, ?, ?)"
                    )
                    
                    sqlite3_bind_text(stmt, 1, name, name.length, SQLITE_TRANSIENT)
                    sqlite3_bind_text(stmt, 2, optionsJson, optionsJson.length, SQLITE_TRANSIENT)
                    sqlite3_bind_int64(stmt, 3, (CFAbsoluteTimeGetCurrent() * 1000).toLong())
                    
                    if (sqlite3_step(stmt) != SQLITE_DONE) {
                        val error = sqlite3_errmsg(dbHandle)?.toKString() ?: "Unknown error"
                        println("Failed to create collection metadata: $error")
                    }
                    
                    sqlite3_finalize(stmt)
                }
            }
            
            SQLiteStorageCollection(name, options, this)
        }
    }
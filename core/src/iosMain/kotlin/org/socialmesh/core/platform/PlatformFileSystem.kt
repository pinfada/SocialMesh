package org.socialmesh.core.platform

import kotlinx.cinterop.*
import platform.Foundation.*
import platform.posix.S_ISDIR
import platform.posix.S_ISREG
import platform.posix.stat
import platform.posix.system

/**
 * Implémentation iOS du fournisseur de système de fichiers
 */
actual class PlatformFileSystem : FileSystemProvider {
    // Gestionnaire de fichiers iOS
    private val fileManager = NSFileManager.defaultManager
    
    // Répertoires de base
    private lateinit var documentDirectory: NSURL
    private lateinit var tmpDirectory: NSURL
    
    override fun initialize(): Boolean {
        try {
            // Obtention des chemins de base
            val documentDirs = fileManager.URLsForDirectory(
                NSDocumentDirectory,
                NSUserDomainMask
            )
            val tempDirs = fileManager.URLsForDirectory(
                NSCachesDirectory,
                NSUserDomainMask
            )
            
            if (documentDirs.isEmpty() || tempDirs.isEmpty()) {
                return false
            }
            
            documentDirectory = documentDirs[0] as NSURL
            tmpDirectory = tempDirs[0] as NSURL
            
            return true
        } catch (e: Exception) {
            return false
        }
    }
    
    override fun close() {
        // Pas de ressources spécifiques à fermer sur iOS
    }
    
    override fun fileExists(path: String): Boolean {
        val resolvedPath = resolvePath(path)
        
        memScoped {
            val isDirectory = alloc<BooleanVar>()
            val exists = fileManager.fileExistsAtPath(resolvedPath, isDirectory.ptr)
            return exists && !isDirectory.value
        }
    }
    
    override fun directoryExists(path: String): Boolean {
        val resolvedPath = resolvePath(path)
        
        memScoped {
            val isDirectory = alloc<BooleanVar>()
            val exists = fileManager.fileExistsAtPath(resolvedPath, isDirectory.ptr)
            return exists && isDirectory.value
        }
    }
    
    override fun ensureDirectoryExists(path: String): Boolean {
        val resolvedPath = resolvePath(path)
        
        memScoped {
            val isDirectory = alloc<BooleanVar>()
            val exists = fileManager.fileExistsAtPath(resolvedPath, isDirectory.ptr)
            
            if (exists && isDirectory.value) {
                return true
            }
            
            if (exists && !isDirectory.value) {
                // C'est un fichier, on ne peut pas créer un répertoire avec le même nom
                return false
            }
            
            val error = alloc<ObjCObjectVar<NSError?>>()
            val success = fileManager.createDirectoryAtPath(
                resolvedPath,
                true,
                null,
                error.ptr
            )
            
            return success
        }
    }
    
    override fun readFile(path: String): ByteArray {
        val resolvedPath = resolvePath(path)
        
        val data = fileManager.contentsAtPath(resolvedPath)
            ?: throw IllegalArgumentException("Cannot read file: $path")
        
        val length = data.length.toInt()
        val bytes = ByteArray(length)
        
        data.getBytes(bytes.refTo(0), length.toULong())
        
        return bytes
    }
    
    override fun writeFile(path: String, data: ByteArray): Boolean {
        val resolvedPath = resolvePath(path)
        
        val nsData = data.usePinned { pinnedData ->
            NSData.dataWithBytes(
                pinnedData.addressOf(0),
                data.size.toULong()
            )
        }
        
        return fileManager.createFileAtPath(
            resolvedPath,
            nsData,
            null
        )
    }
    
    override fun deleteFile(path: String): Boolean {
        val resolvedPath = resolvePath(path)
        
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            return fileManager.removeItemAtPath(resolvedPath, error.ptr)
        }
    }
    
    override fun deleteDirectory(path: String): Boolean {
        val resolvedPath = resolvePath(path)
        
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            return fileManager.removeItemAtPath(resolvedPath, error.ptr)
        }
    }
    
    override fun getFileSize(path: String): Long? {
        val resolvedPath = resolvePath(path)
        
        memScoped {
            val attributes = fileManager.attributesOfItemAtPath(resolvedPath, null)
                ?: return null
            
            val fileSize = attributes[NSFileSize] as? NSNumber
            return fileSize?.longValue
        }
    }
    
    override fun listFiles(path: String): List<String> {
        val resolvedPath = resolvePath(path)
        
        val contents = fileManager.contentsOfDirectoryAtPath(resolvedPath, null)
            ?: return emptyList()
        
        return List(contents.count.toInt()) { i ->
            val fileName = contents[i] as String
            val filePath = "$resolvedPath/$fileName"
            
            // Vérifier si c'est un fichier
            if (isRegularFile(filePath)) {
                filePath
            } else {
                ""
            }
        }.filter { it.isNotEmpty() }
    }
    
    override fun listDirectories(path: String): List<String> {
        val resolvedPath = resolvePath(path)
        
        val contents = fileManager.contentsOfDirectoryAtPath(resolvedPath, null)
            ?: return emptyList()
        
        return List(contents.count.toInt()) { i ->
            val dirName = contents[i] as String
            val dirPath = "$resolvedPath/$dirName"
            
            // Vérifier si c'est un répertoire
            if (isDirectory(dirPath)) {
                dirPath
            } else {
                ""
            }
        }.filter { it.isNotEmpty() }
    }
    
    override fun getAppDirectory(): String {
        return documentDirectory.path ?: ""
    }
    
    override fun getTempDirectory(): String {
        return tmpDirectory.path ?: ""
    }
    
    override fun getAvailableSpace(): Long {
        val attributes = fileManager.attributesOfFileSystemForPath(getAppDirectory(), null)
            ?: return 0L
        
        val freeSpace = attributes[NSFileSystemFreeSize] as? NSNumber
        return freeSpace?.longValue ?: 0L
    }
    
    /**
     * Résout un chemin relatif en chemin absolu
     */
    private fun resolvePath(path: String): String {
        return if (path.startsWith("/")) {
            path
        } else {
            "${getAppDirectory()}/$path"
        }
    }
    
    /**
     * Vérifie si un chemin correspond à un fichier régulier
     */
    private fun isRegularFile(path: String): Boolean {
        memScoped {
            val statBuf = alloc<stat>()
            if (stat(path, statBuf.ptr) != 0) {
                return false
            }
            return S_ISREG(statBuf.st_mode.toInt()) != 0
        }
    }
    
    /**
     * Vérifie si un chemin correspond à un répertoire
     */
    private fun isDirectory(path: String): Boolean {
        memScoped {
            val statBuf = alloc<stat>()
            if (stat(path, statBuf.ptr) != 0) {
                return false
            }
            return S_ISDIR(statBuf.st_mode.toInt()) != 0
        }
    }
}
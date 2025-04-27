package org.socialmesh.core.platform

import android.content.Context
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileOutputStream

/**
 * Implémentation Android du fournisseur de système de fichiers
 */
actual class PlatformFileSystem : FileSystemProvider, KoinComponent {
    // Contexte Android injecté par Koin
    private val appContext: Context by inject()
    
    // Répertoires de base
    private lateinit var internalDir: File
    private lateinit var tempDir: File
    
    override fun initialize(): Boolean {
        try {
            internalDir = appContext.filesDir
            tempDir = appContext.cacheDir
            return true
        } catch (e: Exception) {
            return false
        }
    }
    
    override fun close() {
        // Pas de ressources spécifiques à fermer pour Android
    }
    
    override fun fileExists(path: String): Boolean {
        val file = resolveFile(path)
        return file.exists() && file.isFile
    }
    
    override fun directoryExists(path: String): Boolean {
        val dir = resolveFile(path)
        return dir.exists() && dir.isDirectory
    }
    
    override fun ensureDirectoryExists(path: String): Boolean {
        val dir = resolveFile(path)
        return if (dir.exists()) {
            dir.isDirectory
        } else {
            dir.mkdirs()
        }
    }
    
    override fun readFile(path: String): ByteArray {
        val file = resolveFile(path)
        if (!file.exists() || !file.isFile) {
            throw IllegalArgumentException("File does not exist: $path")
        }
        return file.readBytes()
    }
    
    override fun writeFile(path: String, data: ByteArray): Boolean {
        val file = resolveFile(path)
        
        // Créer les répertoires parents si nécessaire
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        
        return try {
            FileOutputStream(file).use { fos ->
                fos.write(data)
                fos.flush()
            }
            true
        } catch (e: Exception) {
            false
        }
    }
    
    override fun deleteFile(path: String): Boolean {
        val file = resolveFile(path)
        return file.exists() && file.isFile && file.delete()
    }
    
    override fun deleteDirectory(path: String): Boolean {
        val dir = resolveFile(path)
        if (!dir.exists() || !dir.isDirectory) {
            return false
        }
        
        // Suppression récursive
        return deleteRecursively(dir)
    }
    
    override fun getFileSize(path: String): Long? {
        val file = resolveFile(path)
        return if (file.exists() && file.isFile) {
            file.length()
        } else {
            null
        }
    }
    
    override fun listFiles(path: String): List<String> {
        val dir = resolveFile(path)
        if (!dir.exists() || !dir.isDirectory) {
            return emptyList()
        }
        
        return dir.listFiles()
            ?.filter { it.isFile }
            ?.map { it.absolutePath }
            ?: emptyList()
    }
    
    override fun listDirectories(path: String): List<String> {
        val dir = resolveFile(path)
        if (!dir.exists() || !dir.isDirectory) {
            return emptyList()
        }
        
        return dir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.absolutePath }
            ?: emptyList()
    }
    
    override fun getAppDirectory(): String {
        return internalDir.absolutePath
    }
    
    override fun getTempDirectory(): String {
        return tempDir.absolutePath
    }
    
    override fun getAvailableSpace(): Long {
        return internalDir.freeSpace
    }
    
    /**
     * Résout un chemin relatif en fichier absolu
     */
    private fun resolveFile(path: String): File {
        return if (path.startsWith("/")) {
            File(path)
        } else {
            File(internalDir, path)
        }
    }
    
    /**
     * Supprime récursivement un répertoire et son contenu
     */
    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) {
                for (child in children) {
                    if (!deleteRecursively(child)) {
                        return false
                    }
                }
            }
        }
        return file.delete()
    }
}
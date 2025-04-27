package org.socialmesh.core.platform

/**
 * Interface d'abstraction pour l'accès au système de fichiers
 * Les implémentations spécifiques à chaque plateforme (Android, iOS) sont fournies séparément
 */
interface FileSystemProvider {
    /**
     * Initialise le système de fichiers
     * @return true si l'initialisation a réussi
     */
    fun initialize(): Boolean
    
    /**
     * Ferme les ressources du système de fichiers
     */
    fun close()
    
    /**
     * Vérifie si un fichier existe
     * @param path Chemin du fichier
     * @return true si le fichier existe
     */
    fun fileExists(path: String): Boolean
    
    /**
     * Vérifie si un répertoire existe
     * @param path Chemin du répertoire
     * @return true si le répertoire existe
     */
    fun directoryExists(path: String): Boolean
    
    /**
     * Crée un répertoire s'il n'existe pas
     * @param path Chemin du répertoire
     * @return true si le répertoire existe ou a été créé avec succès
     */
    fun ensureDirectoryExists(path: String): Boolean
    
    /**
     * Lit le contenu d'un fichier
     * @param path Chemin du fichier
     * @return Contenu du fichier en tableau d'octets
     * @throws Exception si la lecture échoue
     */
    fun readFile(path: String): ByteArray
    
    /**
     * Écrit du contenu dans un fichier
     * @param path Chemin du fichier
     * @param data Données à écrire
     * @return true si l'écriture a réussi
     * @throws Exception si l'écriture échoue
     */
    fun writeFile(path: String, data: ByteArray): Boolean
    
    /**
     * Supprime un fichier
     * @param path Chemin du fichier
     * @return true si la suppression a réussi
     */
    fun deleteFile(path: String): Boolean
    
    /**
     * Supprime un répertoire et son contenu
     * @param path Chemin du répertoire
     * @return true si la suppression a réussi
     */
    fun deleteDirectory(path: String): Boolean
    
    /**
     * Obtient la taille d'un fichier
     * @param path Chemin du fichier
     * @return Taille du fichier en octets, null si le fichier n'existe pas
     */
    fun getFileSize(path: String): Long?
    
    /**
     * Liste les fichiers dans un répertoire
     * @param path Chemin du répertoire
     * @return Liste des chemins complets des fichiers
     */
    fun listFiles(path: String): List<String>
    
    /**
     * Liste les répertoires dans un répertoire
     * @param path Chemin du répertoire parent
     * @return Liste des chemins complets des répertoires
     */
    fun listDirectories(path: String): List<String>
    
    /**
     * Obtient le chemin du répertoire d'application permanent
     * @return Chemin absolu du répertoire d'application
     */
    fun getAppDirectory(): String
    
    /**
     * Obtient le chemin du répertoire temporaire
     * @return Chemin absolu du répertoire temporaire
     */
    fun getTempDirectory(): String
    
    /**
     * Obtient l'espace disponible sur le stockage
     * @return Espace disponible en octets
     */
    fun getAvailableSpace(): Long
    
    companion object {
        /**
         * Crée une implémentation spécifique à la plateforme
         */
        fun create(): FileSystemProvider = PlatformFileSystem()
    }
}

/**
 * Interface "expect" pour l'implémentation spécifique à la plateforme
 * Chaque plateforme (JVM, Android, iOS, etc.) fournira sa propre implémentation
 */
expect class PlatformFileSystem() : FileSystemProvider
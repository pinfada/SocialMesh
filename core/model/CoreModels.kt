package org.socialmesh.core.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Identifiant unique de ressource dans le système
 */
@Serializable
data class ResourceIdentifier(
    val hash: String,
    val type: ResourceType
) {
    override fun toString(): String = "$type:$hash"
    
    companion object {
        fun fromString(value: String): ResourceIdentifier? {
            val parts = value.split(":")
            if (parts.size != 2) return null
            val type = ResourceType.values().find { it.name == parts[0] } ?: return null
            return ResourceIdentifier(parts[1], type)
        }
    }
}

/**
 * Types de ressources supportés par le système
 */
enum class ResourceType {
    DATA,
    MODULE,
    PROFILE,
    MESSAGE,
    MEDIA,
    CONFIG
}

/**
 * Identité cryptographique d'un pair dans le réseau
 */
@Serializable
data class CryptoIdentity(
    val publicKey: ByteArray,
    val privateKey: ByteArray? = null,
    val algorithm: String = "Ed25519",
    val created: Instant
) {
    val isPublicOnly: Boolean get() = privateKey == null
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as CryptoIdentity

        if (!publicKey.contentEquals(other.publicKey)) return false
        if (privateKey != null) {
            if (other.privateKey == null) return false
            if (!privateKey.contentEquals(other.privateKey)) return false
        } else if (other.privateKey != null) return false
        if (algorithm != other.algorithm) return false
        if (created != other.created) return false

        return true
    }

    override fun hashCode(): Int {
        var result = publicKey.contentHashCode()
        result = 31 * result + (privateKey?.contentHashCode() ?: 0)
        result = 31 * result + algorithm.hashCode()
        result = 31 * result + created.hashCode()
        return result
    }
}

/**
 * Profil utilisateur basique
 */
@Serializable
data class Profile(
    val id: String,
    val displayName: String,
    val avatar: ResourceIdentifier? = null,
    val created: Instant,
    val updated: Instant,
    val status: ProfileStatus = ProfileStatus.ACTIVE,
    val publicIdentity: CryptoIdentity
)

/**
 * Statut d'un profil utilisateur
 */
enum class ProfileStatus {
    ACTIVE, AWAY, OFFLINE, DELETED
}

/**
 * Information sur un module
 */
@Serializable
data class ModuleInfo(
    val id: String,
    val name: String,
    val version: String,
    val developer: String,
    val description: String,
    val size: Long,
    val hash: ResourceIdentifier,
    val signature: ByteArray,
    val requiredPermissions: Set<String>,
    val dependencies: List<String>,
    val created: Instant,
    val compatibilityFlags: Set<String> = emptySet()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ModuleInfo

        if (id != other.id) return false
        if (name != other.name) return false
        if (version != other.version) return false
        if (developer != other.developer) return false
        if (description != other.description) return false
        if (size != other.size) return false
        if (hash != other.hash) return false
        if (!signature.contentEquals(other.signature)) return false
        if (requiredPermissions != other.requiredPermissions) return false
        if (dependencies != other.dependencies) return false
        if (created != other.created) return false
        if (compatibilityFlags != other.compatibilityFlags) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + developer.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + hash.hashCode()
        result = 31 * result + signature.contentHashCode()
        result = 31 * result + requiredPermissions.hashCode()
        result = 31 * result + dependencies.hashCode()
        result = 31 * result + created.hashCode()
        result = 31 * result + compatibilityFlags.hashCode()
        return result
    }
}

/**
 * Erreur système avec niveau et catégorie
 */
@Serializable
data class SystemError(
    val code: Int,
    val message: String,
    val severity: ErrorSeverity,
    val category: ErrorCategory,
    val timestamp: Instant,
    val sourceComponent: String,
    val details: Map<String, String> = emptyMap()
)

/**
 * Niveau de gravité d'une erreur
 */
enum class ErrorSeverity {
    LOW, MEDIUM, HIGH, CRITICAL
}

/**
 * Catégorie d'erreur
 */
enum class ErrorCategory {
    NETWORK, STORAGE, SECURITY, RESOURCE, MODULE, SYSTEM, OTHER
}
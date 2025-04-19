package org.socialmesh.network

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant
import org.socialmesh.core.interfaces.SocialMeshComponent
import org.socialmesh.core.model.CryptoIdentity
import org.socialmesh.core.model.ResourceIdentifier

/**
 * Interface principale pour le service réseau
 */
interface NetworkService : SocialMeshComponent {
    /**
     * Obtient l'identité du nœud local
     */
    suspend fun getLocalIdentity(): CryptoIdentity
    
    /**
     * Démarre le service réseau et active la découverte
     * @return true si le démarrage a réussi
     */
    suspend fun start(): Boolean
    
    /**
     * Arrête le service réseau
     */
    suspend fun stop()
    
    /**
     * Obtient la liste des pairs connus
     * @return Liste des informations sur les pairs
     */
    suspend fun getKnownPeers(): List<PeerInfo>
    
    /**
     * Obtient la liste des pairs connectés
     * @return Liste des informations sur les pairs connectés
     */
    suspend fun getConnectedPeers(): List<PeerInfo>
    
    /**
     * Se connecte à un pair spécifique
     * @param peerId Identifiant du pair
     * @param address Adresse du pair (optionnelle si déjà connue)
     * @return true si la connexion a réussi
     */
    suspend fun connectToPeer(peerId: String, address: String? = null): Boolean
    
    /**
     * Se déconnecte d'un pair
     * @param peerId Identifiant du pair
     * @return true si la déconnexion a réussi
     */
    suspend fun disconnectFromPeer(peerId: String): Boolean
    
    /**
     * Envoi d'un message à un pair
     * @param peerId Identifiant du pair destinataire
     * @param type Type de message
     * @param data Données du message
     * @param priority Priorité du message (0-10)
     * @return Identifiant du message envoyé, null si échec
     */
    suspend fun sendMessage(peerId: String, type: String, data: ByteArray, priority: Int = 5): String?
    
    /**
     * Envoie un message à tous les pairs connectés
     * @param type Type de message
     * @param data Données du message
     * @param priority Priorité du message (0-10)
     * @return Nombre de pairs à qui le message a été envoyé
     */
    suspend fun broadcastMessage(type: String, data: ByteArray, priority: Int = 5): Int
    
    /**
     * Reçoit les messages d'un type spécifique
     * @param type Type de message
     * @return Flux de messages reçus
     */
    fun receiveMessages(type: String): Flow<NetworkMessage>
    
    /**
     * Reçoit tous les messages
     * @return Flux de tous les messages reçus
     */
    fun receiveAllMessages(): Flow<NetworkMessage>
    
    /**
     * Publie une ressource sur le réseau
     * @param resourceId Identifiant de la ressource
     * @param data Données de la ressource
     * @return true si la publication a réussi
     */
    suspend fun publishResource(resourceId: ResourceIdentifier, data: ByteArray): Boolean
    
    /**
     * Récupère une ressource depuis le réseau
     * @param resourceId Identifiant de la ressource
     * @param timeout Timeout en millisecondes (défaut: 30000ms)
     * @return Données de la ressource, null si non trouvée
     */
    suspend fun fetchResource(resourceId: ResourceIdentifier, timeout: Long = 30000): ByteArray?
    
    /**
     * Recherche des pairs par capacité
     * @param capability Capacité recherchée
     * @param value Valeur de la capacité (optionnelle)
     * @return Liste des pairs qui ont la capacité
     */
    suspend fun findPeersByCapability(capability: String, value: String? = null): List<PeerInfo>
    
    /**
     * Définit une capacité locale
     * @param capability Nom de la capacité
     * @param value Valeur de la capacité (optionnelle)
     */
    suspend fun setLocalCapability(capability: String, value: String? = null)
    
    /**
     * Supprime une capacité locale
     * @param capability Nom de la capacité
     */
    suspend fun removeLocalCapability(capability: String)
    
    /**
     * Obtient les statistiques du réseau
     * @return Statistiques réseau
     */
    fun getNetworkStats(): NetworkStats
    
    /**
     * Obtient les événements de connectivité réseau
     * @return Flux d'événements de connectivité
     */
    fun getConnectivityEvents(): Flow<ConnectivityEvent>
}

/**
 * Information sur un pair
 */
data class PeerInfo(
    val id: String,
    val publicKey: ByteArray,
    val addresses: List<String>,
    val lastSeen: Instant,
    val isConnected: Boolean,
    val capabilities: Map<String, String?>,
    val rtt: Long? = null, // Round-Trip Time en ms
    val protocolVersion: String,
    val clientVersion: String?,
    val reputation: Int // 0-100
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as PeerInfo

        if (id != other.id) return false
        if (!publicKey.contentEquals(other.publicKey)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }
}

/**
 * Message réseau
 */
data class NetworkMessage(
    val id: String,
    val senderId: String,
    val type: String,
    val data: ByteArray,
    val timestamp: Instant,
    val isEncrypted: Boolean,
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as NetworkMessage

        if (id != other.id) return false
        if (senderId != other.senderId) return false
        if (type != other.type) return false
        if (!data.contentEquals(other.data)) return false
        if (timestamp != other.timestamp) return false
        if (isEncrypted != other.isEncrypted) return false
        if (!signature.contentEquals(other.signature)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + senderId.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + isEncrypted.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

/**
 * Statistiques réseau
 */
data class NetworkStats(
    val totalPeers: Int,
    val connectedPeers: Int,
    val incomingBandwidthBytesPerSecond: Long,
    val outgoingBandwidthBytesPerSecond: Long,
    val messagesSent: Long,
    val messagesReceived: Long,
    val messagesFailed: Long,
    val resourcesPublished: Int,
    val resourcesFetched: Int,
    val resourcesRequested: Int,
    val connectionsInitiated: Long,
    val connectionsFailed: Long
)

/**
 * Type d'événement de connectivité
 */
enum class ConnectivityEventType {
    CONNECTED,
    DISCONNECTED,
    NETWORK_CHANGED
}

/**
 * Événement de connectivité
 */
data class ConnectivityEvent(
    val type: ConnectivityEventType,
    val timestamp: Instant,
    val isInternetAvailable: Boolean,
    val networkType: String?
)

/**
 * Interface pour la découverte de pairs
 */
interface PeerDiscovery : SocialMeshComponent {
    /**
     * Démarre la découverte
     * @param peerFoundCallback Callback appelé quand un pair est trouvé
     */
    suspend fun startDiscovery(peerFoundCallback: suspend (PeerInfo) -> Unit): Boolean
    
    /**
     * Arrête la découverte
     */
    suspend fun stopDiscovery()
    
    /**
     * Indique si la découverte est active
     */
    fun isDiscoveryActive(): Boolean
    
    /**
     * Commence à annoncer la présence locale
     */
    suspend fun startAdvertising(): Boolean
    
    /**
     * Arrête d'annoncer la présence locale
     */
    suspend fun stopAdvertising()
    
    /**
     * Indique si l'annonce est active
     */
    fun isAdvertisingActive(): Boolean
}

/**
 * Interface pour le transport réseau
 */
interface NetworkTransport : SocialMeshComponent {
    /**
     * Se connecte à un pair
     * @param peerId Identifiant du pair
     * @param address Adresse du pair
     * @return true si la connexion a réussi
     */
    suspend fun connect(peerId: String, address: String): Boolean
    
    /**
     * Se déconnecte d'un pair
     * @param peerId Identifiant du pair
     */
    suspend fun disconnect(peerId: String)
    
    /**
     * Envoie un message à un pair
     * @param peerId Identifiant du pair
     * @param data Données du message
     */
    suspend fun sendData(peerId: String, data: ByteArray)
    
    /**
     * Reçoit des données
     * @return Flux de paires (peerId, data)
     */
    fun receiveData(): Flow<Pair<String, ByteArray>>
    
    /**
     * Obtient la liste des pairs connectés
     */
    fun getConnectedPeers(): List<String>
    
    /**
     * Vérifie si un pair est connecté
     */
    fun isPeerConnected(peerId: String): Boolean
}

/**
 * Interface pour la gestion de la réputation des pairs
 */
interface PeerReputationManager : SocialMeshComponent {
    /**
     * Obtient la réputation d'un pair
     * @param peerId Identifiant du pair
     * @return Réputation (0-100), -1 si inconnu
     */
    suspend fun getReputation(peerId: String): Int
    
    /**
     * Ajuste la réputation d'un pair
     * @param peerId Identifiant du pair
     * @param adjustment Ajustement positif ou négatif
     * @param reason Raison de l'ajustement
     */
    suspend fun adjustReputation(peerId: String, adjustment: Int, reason: String)
    
    /**
     * Vérifie si un pair est fiable
     * @param peerId Identifiant du pair
     * @param minimumThreshold Seuil minimum de réputation (défaut: 30)
     * @return true si le pair est fiable
     */
    suspend fun isTrusted(peerId: String, minimumThreshold: Int = 30): Boolean
    
    /**
     * Obtient la liste des pairs classés par réputation
     * @param threshold Seuil minimum de réputation (optionnel)
     * @return Liste des pairs et leur réputation, triée par réputation décroissante
     */
    suspend fun getRankedPeers(threshold: Int? = null): List<Pair<String, Int>>
}
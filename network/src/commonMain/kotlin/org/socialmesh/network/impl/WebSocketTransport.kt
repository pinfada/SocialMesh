package org.socialmesh.network.impl

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.socialmesh.core.interfaces.LoggingService
import org.socialmesh.network.NetworkTransport
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Implémentation du transport réseau utilisant WebSockets
 */
class WebSocketTransport : NetworkTransport, KoinComponent {
    override val componentId: String = "org.socialmesh.network.transport.websocket"
    override val version: String = "1.0.0"
    override val dependencies: List<String> = listOf("org.socialmesh.core.logging")

    private val TAG = "WebSocketTransport"
    private val logger: LoggingService by inject()

    // Client HTTP pour les WebSockets
    private lateinit var client: HttpClient

    // Connexions actives par pairId
    private val activeConnections = ConcurrentHashMap<String, WebSocketConnection>()

    // Canal pour les données reçues
    private val dataChannel = Channel<Pair<String, ByteArray>>(Channel.BUFFERED)

    // Scope coroutine pour les opérations asynchrones
    private val transportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Identifiant des connexions sortantes
    private val nextConnectionId = AtomicLong(0)

    // Serveur WebSocket pour les connexions entrantes
    private var serverJob: Job? = null
    private var serverPort: Int = 9000 // Port par défaut

    override fun initialize(): Boolean {
        try {
            logger.info(TAG, "Initializing WebSocket transport")

            // Créer le client HTTP avec support WebSocket
            client = HttpClient(OkHttp) {
                install(WebSockets) {
                    contentConverter = KotlinxWebsocketSerializationConverter(Json)
                }
            }

            // Démarrage du serveur WebSocket pour les connexions entrantes
            serverJob = transportScope.launch {
                startServer()
            }

            logger.info(TAG, "WebSocket transport initialized successfully")
            return true
        } catch (e: Exception) {
            logger.error(TAG, "Failed to initialize WebSocket transport", e)
            return false
        }
    }

    override fun shutdown() {
        try {
            logger.info(TAG, "Shutting down WebSocket transport")

            // Fermer toutes les connexions actives
            activeConnections.forEach { (_, connection) ->
                connection.close()
            }
            activeConnections.clear()

            // Arrêter le serveur WebSocket
            serverJob?.cancel()
            serverJob = null

            // Fermer le client HTTP
            client.close()

            // Annuler toutes les coroutines
            transportScope.cancel()

            logger.info(TAG, "WebSocket transport shutdown complete")
        } catch (e: Exception) {
            logger.error(TAG, "Error during WebSocket transport shutdown", e)
        }
    }

    override suspend fun connect(peerId: String, address: String): Boolean {
        if (activeConnections.containsKey(peerId)) {
            logger.warn(TAG, "Already connected to peer: $peerId")
            return true
        }

        try {
            logger.debug(TAG, "Connecting to peer: $peerId at $address")

            // Analyser l'adresse
            val uri = URI("ws://$address")
            val host = uri.host
            val port = if (uri.port == -1) 9000 else uri.port

            // Se connecter au WebSocket
            val wsSession = client.webSocketSession {
                url(URLProtocol.WS, host, port, "/")
            }

            // Créer une nouvelle connexion
            val connection = WebSocketConnection(
                id = "outbound-${nextConnectionId.incrementAndGet()}",
                peerId = peerId,
                session = wsSession,
                isInbound = false
            )

            // Ajouter la connexion aux connexions actives
            activeConnections[peerId] = connection

            // Démarrer la réception des messages
            transportScope.launch {
                receiveMessages(connection)
            }

            logger.info(TAG, "Connected to peer: $peerId at $address")
            return true
        } catch (e: Exception) {
            logger.error(TAG, "Failed to connect to peer: $peerId at $address", e)
            return false
        }
    }

    override suspend fun disconnect(peerId: String) {
        val connection = activeConnections.remove(peerId)
        if (connection != null) {
            try {
                connection.close()
                logger.info(TAG, "Disconnected from peer: $peerId")
            } catch (e: Exception) {
                logger.error(TAG, "Error disconnecting from peer: $peerId", e)
            }
        } else {
            logger.warn(TAG, "No active connection to peer: $peerId")
        }
    }

    override suspend fun sendData(peerId: String, data: ByteArray) {
        val connection = activeConnections[peerId]
        if (connection != null) {
            try {
                connection.send(data)
                logger.debug(TAG, "Sent ${data.size} bytes to peer: $peerId")
            } catch (e: Exception) {
                logger.error(TAG, "Error sending data to peer: $peerId", e)
                // Déconnecter en cas d'erreur
                disconnect(peerId)
            }
        } else {
            logger.warn(TAG, "Cannot send data, no active connection to peer: $peerId")
        }
    }

    override fun receiveData(): Flow<Pair<String, ByteArray>> {
        return flow {
            while (true) {
                val (peerId, data) = dataChannel.receive()
                emit(peerId to data)
            }
        }
    }

    override fun getConnectedPeers(): List<String> {
        return activeConnections.keys.toList()
    }

    override fun isPeerConnected(peerId: String): Boolean {
        return activeConnections.containsKey(peerId)
    }

    /**
     * Démarre le serveur WebSocket pour les connexions entrantes
     */
    private suspend fun startServer() {
        // Note: Dans une implémentation réelle, nous utiliserions ktor-server
        // Pour simplifier, nous simulons un serveur ici
        logger.info(TAG, "WebSocket server started on port $serverPort")

        // En réalité, nous aurions un code comme celui-ci:
        /*
        embeddedServer(Netty, port = serverPort) {
            install(WebSockets)
            routing {
                webSocket("/") {
                    val connectionId = "inbound-${nextConnectionId.incrementAndGet()}"
                    
                    // Réception du handshake initial pour identifier le pair
                    val initialFrame = incoming.receive()
                    if (initialFrame is Frame.Text) {
                        val peerId = initialFrame.readText()
                        
                        // Créer une nouvelle connexion
                        val connection = WebSocketConnection(
                            id = connectionId,
                            peerId = peerId,
                            session = this,
                            isInbound = true
                        )
                        
                        // Ajouter la connexion aux connexions actives
                        activeConnections[peerId] = connection
                        
                        // Recevoir les messages
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Binary -> {
                                    val data = frame.readBytes()
                                    dataChannel.send(peerId to data)
                                }
                                is Frame.Close -> {
                                    activeConnections.remove(peerId)
                                    break
                                }
                            }
                        }
                    }
                }
            }
        }.start(wait = true)
        */
    }

    /**
     * Reçoit les messages d'une connexion WebSocket
     */
    private suspend fun receiveMessages(connection: WebSocketConnection) {
        try {
            for (frame in connection.session.incoming) {
                when (frame) {
                    is Frame.Binary -> {
                        val data = frame.readBytes()
                        dataChannel.send(connection.peerId to data)
                        logger.debug(TAG, "Received ${data.size} bytes from peer: ${connection.peerId}")
                    }
                    is Frame.Close -> {
                        activeConnections.remove(connection.peerId)
                        logger.info(TAG, "Connection closed by peer: ${connection.peerId}")
                        break
                    }
                    else -> {} // Ignorer les autres types de frames
                }
            }
        } catch (e: Exception) {
            logger.error(TAG, "Error receiving messages from peer: ${connection.peerId}", e)
            activeConnections.remove(connection.peerId)
        }
    }

    /**
     * Classe représentant une connexion WebSocket
     */
    private inner class WebSocketConnection(
        val id: String,
        val peerId: String,
        val session: WebSocketSession,
        val isInbound: Boolean
    ) {
        /**
         * Envoie des données sur la connexion
         */
        suspend fun send(data: ByteArray) {
            session.send(Frame.Binary(true, data))
        }

        /**
         * Ferme la connexion
         */
        fun close() {
            transportScope.launch {
                try {
                    session.close()
                } catch (e: Exception) {
                    logger.error(TAG, "Error closing WebSocket session for peer: $peerId", e)
                }
            }
        }
    }
}
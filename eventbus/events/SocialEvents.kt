package org.socialmesh.eventbus.events

import kotlinx.serialization.Serializable
import org.socialmesh.core.model.ResourceIdentifier

/**
 * Événement de base pour les interactions sociales
 */
@Serializable
sealed class SocialEvent : BaseEvent() {
    abstract val profileId: String
}

/**
 * Événement de mise à jour de profil
 */
@Serializable
class ProfileUpdatedEvent(
    override val source: String,
    override val profileId: String,
    val displayName: String?,
    val avatar: ResourceIdentifier?,
    val status: String?,
    override val metadata: Map<String, String> = emptyMap()
) : SocialEvent() {
    override val type: String = "social.profile.updated"
}

/**
 * Événement de création de profil
 */
@Serializable
class ProfileCreatedEvent(
    override val source: String,
    override val profileId: String,
    val displayName: String,
    override val metadata: Map<String, String> = emptyMap()
) : SocialEvent() {
    override val type: String = "social.profile.created"
}

/**
 * Événement de suppression de profil
 */
@Serializable
class ProfileDeletedEvent(
    override val source: String,
    override val profileId: String,
    override val metadata: Map<String, String> = emptyMap()
) : SocialEvent() {
    override val type: String = "social.profile.deleted"
}

/**
 * Événement de connexion/relation entre profils
 */
@Serializable
class ConnectionEvent(
    override val source: String,
    override val profileId: String,
    val targetProfileId: String,
    val action: ConnectionAction,
    val relationshipType: String = "default",
    override val metadata: Map<String, String> = emptyMap()
) : SocialEvent() {
    override val type: String = "social.connection.${action.name.lowercase()}"
}

/**
 * Types d'actions de connexion
 */
enum class ConnectionAction {
    REQUESTED,
    ACCEPTED,
    DECLINED,
    REMOVED
}

/**
 * Événement de message
 */
@Serializable
class MessageEvent(
    override val source: String,
    override val profileId: String,
    val targetId: String,
    val messageId: String,
    val messageType: MessageType,
    val contentRef: ResourceIdentifier?,
    val encrypted: Boolean = true,
    override val metadata: Map<String, String> = emptyMap()
) : SocialEvent() {
    override val type: String = "social.message.${messageType.name.lowercase()}"
}

/**
 * Types de messages
 */
enum class MessageType {
    TEXT,
    MEDIA,
    LOCATION,
    SYSTEM,
    ENCRYPTED
}

/**
 * Événement de groupe/communauté
 */
@Serializable
sealed class CommunityEvent : BaseEvent() {
    abstract val communityId: String
}

/**
 * Événement de création de communauté
 */
@Serializable
class CommunityCreatedEvent(
    override val source: String,
    override val communityId: String,
    val name: String,
    val creatorId: String,
    val description: String,
    val isPrivate: Boolean,
    override val metadata: Map<String, String> = emptyMap()
) : CommunityEvent() {
    override val type: String = "social.community.created"
}

/**
 * Événement de mise à jour de communauté
 */
@Serializable
class CommunityUpdatedEvent(
    override val source: String,
    override val communityId: String,
    val name: String?,
    val description: String?,
    val isPrivate: Boolean?,
    override val metadata: Map<String, String> = emptyMap()
) : CommunityEvent() {
    override val type: String = "social.community.updated"
}

/**
 * Événement de suppression de communauté
 */
@Serializable
class CommunityDeletedEvent(
    override val source: String,
    override val communityId: String,
    override val metadata: Map<String, String> = emptyMap()
) : CommunityEvent() {
    override val type: String = "social.community.deleted"
}

/**
 * Événement de membre de communauté
 */
@Serializable
class CommunityMemberEvent(
    override val source: String,
    override val communityId: String,
    val profileId: String,
    val action: MemberAction,
    val role: String?,
    override val metadata: Map<String, String> = emptyMap()
) : CommunityEvent() {
    override val type: String = "social.community.member.${action.name.lowercase()}"
}

/**
 * Types d'actions de membre
 */
enum class MemberAction {
    JOINED,
    LEFT,
    INVITED,
    KICKED,
    ROLE_CHANGED
}

/**
 * Événement de contenu dans la communauté
 */
@Serializable
class CommunityContentEvent(
    override val source: String,
    override val communityId: String,
    val contentId: String,
    val authorId: String,
    val contentType: String,
    val contentRef: ResourceIdentifier?,
    val action: ContentAction,
    override val metadata: Map<String, String> = emptyMap()
) : CommunityEvent() {
    override val type: String = "social.community.content.${action.name.lowercase()}"
}

/**
 * Types d'actions sur le contenu
 */
enum class ContentAction {
    CREATED,
    UPDATED,
    DELETED,
    REACTED
}

/**
 * Événement de découverte
 */
@Serializable
class DiscoveryEvent(
    override val source: String,
    val discoveryType: DiscoveryType,
    val resourceId: String,
    val resourceType: String,
    val score: Float,
    val reason: String,
    override val metadata: Map<String, String> = emptyMap()
) : BaseEvent() {
    override val type: String = "social.discovery.${discoveryType.name.lowercase()}"
}

/**
 * Types de découverte
 */
enum class DiscoveryType {
    PROFILE,
    COMMUNITY,
    CONTENT,
    MODULE
}
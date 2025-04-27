// Dans src/test/kotlin/org/socialmesh/core/SocialMeshContainerTest.kt
package org.socialmesh.core

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue

class SocialMeshContainerTest {
    @Test
    fun `Container initializes successfully`() {
        val container = SocialMeshContainer()
        assertTrue(container.initialize())
        container.shutdown()
    }
}
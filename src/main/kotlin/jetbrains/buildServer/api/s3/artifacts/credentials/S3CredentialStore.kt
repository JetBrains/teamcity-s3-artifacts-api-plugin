/*
 * Copyright 2000-2025 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.api.s3.artifacts.credentials

import jetbrains.buildServer.serverSide.crypt.EncryptUtil
import jetbrains.buildServer.users.PropertyKey
import jetbrains.buildServer.users.SUser
import jetbrains.buildServer.users.SimplePropertyKey
import org.springframework.stereotype.Service
import java.security.SecureRandom

/**
 * Stores and retrieves each user's S3 secret.
 *
 * The S3 access key id is the user's TeamCity username, so a user has at most one
 * active secret at a time. The secret is generated server-side, kept
 * reversibly-encrypted in a user-profile property (so the SigV4 HMAC can be
 * recomputed on each request), and never persisted or returned in plaintext except
 * once, at generation time. There is no default/fallback secret: a user without a
 * provisioned secret cannot authenticate.
 */
@Service
class S3CredentialStore {
    companion object {
        val SECRET_PROPERTY_KEY: PropertyKey = SimplePropertyKey("teamcity.s3api.secret")

        private const val SECRET_LENGTH = 40
        private const val SECRET_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private val RANDOM = SecureRandom()
    }

    /**
     * Generates a fresh secret for the user, replacing any existing one, stores it
     * encrypted, and returns the plaintext. This is the only time the plaintext is
     * exposed.
     */
    fun generate(user: SUser): String {
        val secret = randomSecret()
        user.setUserProperty(SECRET_PROPERTY_KEY, EncryptUtil.scramble(secret))
        return secret
    }

    /**
     * Returns the user's decrypted secret, or null when none is provisioned or the stored value is
     * unusable. Fails closed on a malformed stored value rather than throwing (see [storedSecret]).
     */
    fun load(user: SUser): String? = storedSecret(user)?.let { EncryptUtil.unscramble(it) }

    /** Whether the user currently has a usable secret provisioned. */
    fun exists(user: SUser): Boolean = storedSecret(user) != null

    /** Removes the user's stored secret, disabling S3 access until it is regenerated. */
    fun revoke(user: SUser) {
        user.deleteUserProperty(SECRET_PROPERTY_KEY)
    }

    /**
     * The stored, still-encrypted secret for [user], or null when there is no usable one: the
     * property is absent/empty, or holds a value that is not valid reversibly-encrypted data (e.g.
     * hand-edited, or written by a different scheme). Returning null there makes [load] and [exists]
     * fail closed, instead of letting [EncryptUtil.unscramble] throw and surface as a 500.
     */
    private fun storedSecret(user: SUser): String? {
        val stored = user.getPropertyValue(SECRET_PROPERTY_KEY)
        return if (stored.isNullOrEmpty() || !EncryptUtil.isScrambled(stored)) null else stored
    }

    private fun randomSecret(): String {
        val builder = StringBuilder(SECRET_LENGTH)
        repeat(SECRET_LENGTH) {
            builder.append(SECRET_ALPHABET[RANDOM.nextInt(SECRET_ALPHABET.length)])
        }
        return builder.toString()
    }
}

/*
 * Copyright 2026 Henry Nguyen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.thehenrylabs.aetherenglish

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreSecretStore(directory: File) : SecretStore {
    private val file = File(directory, "secrets.bin")
    private val lock = Any()

    override suspend fun put(key: String, value: String) {
        synchronized(lock) {
            val rows = readRows()
            rows[key] = encrypt(value)
            writeRows(rows)
        }
    }

    override suspend fun get(key: String): String? {
        return synchronized(lock) {
            val encoded = readRows()[key] ?: return@synchronized null
            decrypt(encoded)
        }
    }

    override suspend fun delete(key: String) {
        synchronized(lock) {
            val rows = readRows()
            rows.remove(key)
            writeRows(rows)
        }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE)
        store.load(null)
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(value.encodeToByteArray())
        val packed = ByteArray(iv.size + body.size)
        iv.copyInto(packed)
        body.copyInto(packed, iv.size)
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = packed.copyOfRange(0, GCM_IV_BYTES)
        val body = packed.copyOfRange(GCM_IV_BYTES, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(body).decodeToString()
    }

    private fun readRows(): MutableMap<String, String> {
        if (!file.exists()) return mutableMapOf()
        val rows = mutableMapOf<String, String>()
        file.readLines().forEach { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEach
            rows[line.substring(0, tab)] = line.substring(tab + 1)
        }
        return rows
    }

    private fun writeRows(rows: Map<String, String>) {
        file.parentFile?.mkdirs()
        val text = rows.entries.joinToString("\n") { (key, value) -> "$key\t$value" }
        file.writeText(text)
    }

    private companion object {
        const val ANDROID_KEYSTORE: String = "AndroidKeyStore"
        const val ALIAS: String = "aether-secret-key"
        const val TRANSFORMATION: String = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES: Int = 12
        const val GCM_TAG_BITS: Int = 128
    }
}

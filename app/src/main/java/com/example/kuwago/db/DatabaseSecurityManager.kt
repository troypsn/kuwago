package com.example.kuwago.db

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object DatabaseSecurityManager {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "kuwago_db_passphrase_key"
    private const val PREFS_NAME = "kuwago_sec_db_prefs"
    private const val KEY_ENCRYPTED_PASSPHRASE = "enc_db_passphrase"
    private const val KEY_IV = "enc_db_iv"
    private const val AES_GCM_TAG_LENGTH = 128
    private const val PASSPHRASE_SIZE_BYTES = 32

    @Synchronized
    fun getOrGeneratePassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedEncryptedB64 = prefs.getString(KEY_ENCRYPTED_PASSPHRASE, null)
        val storedIvB64 = prefs.getString(KEY_IV, null)

        val secretKey = getOrCreateKeyStoreKey()

        if (!storedEncryptedB64.isNullOrEmpty() && !storedIvB64.isNullOrEmpty()) {
            return try {
                val cipherText = Base64.decode(storedEncryptedB64, Base64.NO_WRAP)
                val iv = Base64.decode(storedIvB64, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val spec = GCMParameterSpec(AES_GCM_TAG_LENGTH, iv)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
                cipher.doFinal(cipherText)
            } catch (e: Exception) {
                // In case of corruption or key invalidation, regenerate passphrase safely
                generateAndSavePassphrase(context, secretKey)
            }
        } else {
            return generateAndSavePassphrase(context, secretKey)
        }
    }

    private fun generateAndSavePassphrase(context: Context, secretKey: SecretKey): ByteArray {
        val rawPassphrase = ByteArray(PASSPHRASE_SIZE_BYTES)
        SecureRandom().nextBytes(rawPassphrase)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encryptedPassphrase = cipher.doFinal(rawPassphrase)

        val encB64 = Base64.encodeToString(encryptedPassphrase, Base64.NO_WRAP)
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENCRYPTED_PASSPHRASE, encB64)
            .putString(KEY_IV, ivB64)
            .apply()

        return rawPassphrase
    }

    private fun getOrCreateKeyStoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry?.secretKey != null) {
                return entry.secretKey
            }
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}

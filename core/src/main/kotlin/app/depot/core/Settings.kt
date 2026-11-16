package app.depot.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Everything the store remembers. The token is encrypted with a key held by the Android Keystore. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("depot", Context.MODE_PRIVATE)

    var token: String?
        get() = prefs.getString("token", null)?.let { runCatching { Crypto.decrypt(it) }.getOrNull() }
        set(value) {
            val clean = value?.trim().orEmpty()
            prefs.edit().apply {
                if (clean.isEmpty()) remove("token") else putString("token", Crypto.encrypt(clean))
            }.apply()
        }

    var repos: List<RepoEntry>
        get() = reposFromJson(prefs.getString("repos", "[]") ?: "[]")
        set(value) = prefs.edit().putString("repos", reposToJson(value)).apply()

    var autoCheck: Boolean
        get() = prefs.getBoolean("autoCheck", true)
        set(value) = prefs.edit().putBoolean("autoCheck", value).apply()

    var autoInstall: Boolean
        get() = prefs.getBoolean("autoInstall", false)
        set(value) = prefs.edit().putBoolean("autoInstall", value).apply()

    var syncToWatch: Boolean
        get() = prefs.getBoolean("syncToWatch", true)
        set(value) = prefs.edit().putBoolean("syncToWatch", value).apply()

    var lastCheck: Long
        get() = prefs.getLong("lastCheck", 0)
        set(value) = prefs.edit().putLong("lastCheck", value).apply()

    fun record(key: String): InstallRecord {
        val o = prefs.getString("rec:$key", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return InstallRecord()
        return InstallRecord(
            packageName = o.optString("pkg").ifEmpty { null },
            label = o.optString("label").ifEmpty { null },
            assetId = o.optLong("asset"),
        )
    }

    fun setRecord(key: String, record: InstallRecord) {
        val o = JSONObject()
            .put("pkg", record.packageName.orEmpty())
            .put("label", record.label.orEmpty())
            .put("asset", record.assetId)
        prefs.edit().putString("rec:$key", o.toString()).apply()
    }

    /** Last release seen for a repository, so the list is filled in before the network answers. */
    fun cachedRelease(key: String): Release? {
        val o = prefs.getString("rel:$key", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val assets = o.optJSONArray("assets") ?: JSONArray()
        return Release(
            tag = o.optString("tag"),
            title = o.optString("title"),
            notes = o.optString("notes"),
            publishedAt = o.optString("published"),
            assets = (0 until assets.length()).map {
                val a = assets.getJSONObject(it)
                Asset(a.getLong("id"), a.getString("name"), a.optLong("size"))
            },
        )
    }

    fun setCachedRelease(key: String, release: Release?) {
        if (release == null) {
            prefs.edit().remove("rel:$key").apply()
            return
        }
        val assets = JSONArray()
        release.assets.forEach { assets.put(JSONObject().put("id", it.id).put("name", it.name).put("size", it.size)) }
        val o = JSONObject()
            .put("tag", release.tag)
            .put("title", release.title)
            .put("notes", release.notes.take(4000))
            .put("published", release.publishedAt)
            .put("assets", assets)
        prefs.edit().putString("rel:$key", o.toString()).apply()
    }

    fun forget(key: String) = prefs.edit().remove("rec:$key").remove("rel:$key").apply()

    companion object {
        fun reposToJson(repos: List<RepoEntry>): String {
            val arr = JSONArray()
            repos.forEach {
                arr.put(
                    JSONObject().put("owner", it.owner).put("name", it.name)
                        .put("target", it.target.name).put("prerelease", it.prerelease)
                )
            }
            return arr.toString()
        }

        fun reposFromJson(json: String): List<RepoEntry> = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                RepoEntry(
                    owner = o.getString("owner"),
                    name = o.getString("name"),
                    target = runCatching { Target.valueOf(o.optString("target")) }.getOrDefault(Target.AUTO),
                    prerelease = o.optBoolean("prerelease"),
                )
            }
        }.getOrDefault(emptyList())
    }
}

private object Crypto {
    private const val ALIAS = "depot-token"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(spec)
            generateKey()
        }
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return String(cipher.doFinal(raw, 12, raw.size - 12))
    }
}

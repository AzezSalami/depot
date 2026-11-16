// Applied by :phone and :wear. Both apps must share the application id and the
// signing key, otherwise the phone cannot hand its settings to the watch.
import java.util.Properties

val keystoreFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
}
fun prop(name: String, env: String): String? =
    keystoreProps.getProperty(name) ?: System.getenv(env)

extra["signingStoreFile"] = prop("storeFile", "DEPOT_KEYSTORE_FILE")
extra["signingStorePassword"] = prop("storePassword", "DEPOT_KEYSTORE_PASSWORD")
extra["signingKeyAlias"] = prop("keyAlias", "DEPOT_KEY_ALIAS")
extra["signingKeyPassword"] = prop("keyPassword", "DEPOT_KEY_PASSWORD")

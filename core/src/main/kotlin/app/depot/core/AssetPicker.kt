package app.depot.core

/** Chooses which APK of a release belongs on this device. */
object AssetPicker {
    private val WEAR = Regex("(wear|watch)", RegexOption.IGNORE_CASE)
    // x86_64 must come before x86 so the longer name wins.
    private val ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    fun isWearName(name: String) = WEAR.containsMatchIn(name)

    fun pick(assets: List<Asset>, variant: Variant, target: Target, deviceAbis: List<String>): Asset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null
        val wear = apks.filter { isWearName(it.name) }
        val other = apks - wear.toSet()
        // With AUTO, a release whose APKs are all named for the watch is a watch app,
        // and a release with no such names is a phone app.
        val candidates = when (variant) {
            Variant.PHONE -> when (target) {
                Target.WATCH -> emptyList()
                Target.PHONE -> other.ifEmpty { apks }
                Target.AUTO, Target.BOTH -> other
            }
            Variant.WEAR -> when (target) {
                Target.PHONE -> emptyList()
                Target.WATCH -> wear.ifEmpty { apks }
                Target.AUTO, Target.BOTH -> wear
            }
        }
        return candidates
            .mapNotNull { a -> score(a.name, deviceAbis)?.let { it to a } }
            .minByOrNull { it.first }
            ?.second
    }

    /** Lower is better; null means the APK cannot run on this device. */
    private fun score(name: String, deviceAbis: List<String>): Int? {
        val lower = name.lowercase()
        val abi = ABIS.firstOrNull { lower.contains(it) }
        var score = when (abi) {
            null -> deviceAbis.size
            else -> deviceAbis.indexOf(abi).takeIf { it >= 0 } ?: return null
        }
        if (lower.contains("debug")) score += 100
        if (lower.contains("unsigned")) score += 1000
        return score
    }
}

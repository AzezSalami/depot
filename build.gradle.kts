plugins {
    id("com.android.application") version "9.5.1" apply false
    id("com.android.library") version "9.5.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}

// Version shared by the phone and watch apps.
// Change depotVersionBase by hand for a new major or minor version. The last number and the
// versionCode come from DEPOT_BUILD_NUMBER, which the release workflow sets to its run number,
// so every release is newer than the one before. Local builds are build 0.
val depotVersionBase = "1.0"
val depotBuildNumber = (System.getenv("DEPOT_BUILD_NUMBER") ?: "0").toInt()
extra["depotVersionCode"] = 100 + depotBuildNumber
extra["depotVersionName"] = "$depotVersionBase.$depotBuildNumber"

tasks.register("printVersion") {
    val name = "$depotVersionBase.$depotBuildNumber"
    doLast { println(name) }
}

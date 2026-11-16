package app.depot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoreTest {
    private val arm = listOf("arm64-v8a", "armeabi-v7a")
    private fun a(vararg names: String) = names.mapIndexed { i, n -> Asset(i + 1L, n, 10) }
    private fun pick(assets: List<Asset>, v: Variant, t: Target = Target.AUTO, abis: List<String> = arm) =
        AssetPicker.pick(assets, v, t, abis)?.name

    @Test fun splitsPhoneAndWatchByName() {
        val assets = a("app-phone-release.apk", "app-wear-release.apk", "notes.txt")
        assertEquals("app-phone-release.apk", pick(assets, Variant.PHONE))
        assertEquals("app-wear-release.apk", pick(assets, Variant.WEAR))
    }

    @Test fun autoSendsPlainApkToPhoneOnly() {
        val assets = a("app-release.apk")
        assertEquals("app-release.apk", pick(assets, Variant.PHONE))
        assertNull(pick(assets, Variant.WEAR))
    }

    @Test fun autoSendsWatchNamedApkToWatchOnly() {
        val assets = a("watchface-release.apk")
        assertNull(pick(assets, Variant.PHONE))
        assertEquals("watchface-release.apk", pick(assets, Variant.WEAR))
    }

    @Test fun explicitTargetOverridesNames() {
        assertEquals("app-release.apk", pick(a("app-release.apk"), Variant.WEAR, Target.WATCH))
        assertNull(pick(a("app-release.apk"), Variant.PHONE, Target.WATCH))
        assertEquals("watchface.apk", pick(a("watchface.apk"), Variant.PHONE, Target.PHONE))
        assertNull(pick(a("watchface.apk"), Variant.WEAR, Target.PHONE))
    }

    @Test fun prefersMatchingAbiAndSkipsForeignOnes() {
        val assets = a("app-x86_64-release.apk", "app-armeabi-v7a-release.apk", "app-arm64-v8a-release.apk")
        assertEquals("app-arm64-v8a-release.apk", pick(assets, Variant.PHONE))
        assertEquals("app-armeabi-v7a-release.apk", pick(assets, Variant.PHONE, abis = listOf("armeabi-v7a")))
        assertEquals("app-x86_64-release.apk", pick(assets, Variant.PHONE, abis = listOf("x86_64", "x86")))
        assertNull(pick(a("app-x86-release.apk"), Variant.PHONE))
    }

    @Test fun prefersReleaseOverDebug() {
        assertEquals("app-release.apk", pick(a("app-debug.apk", "app-release.apk"), Variant.PHONE))
        assertEquals("app-release.apk", pick(a("app-release-unsigned.apk", "app-release.apk"), Variant.PHONE))
    }

    @Test fun noApkMeansNothing() = assertNull(pick(a("source.zip"), Variant.PHONE))

    @Test fun parsesRepoInput() {
        assertEquals("me/app", RepoEntry.parse("me/app")?.key)
        assertEquals("me/my.app", RepoEntry.parse(" https://github.com/me/my.app/releases/tag/v1 ")?.key)
        assertEquals("me/app", RepoEntry.parse("git@github.com:me/app.git")?.key)
        assertEquals("me/app", RepoEntry.parse("github.com/me/app/")?.key)
        assertNull(RepoEntry.parse("just-a-name"))
        assertNull(RepoEntry.parse("me/bad name"))
    }

    @Test fun parsesReleasesSkippingDraftsAndPrereleases() {
        val json = """[
          {"tag_name":"v3","draft":true,"prerelease":false,"assets":[]},
          {"tag_name":"v2-beta","name":null,"draft":false,"prerelease":true,"body":null,
           "assets":[{"id":7,"name":"b.apk","size":5}]},
          {"tag_name":"v1","name":"First","draft":false,"prerelease":false,"body":"notes",
           "published_at":"2026-01-01T00:00:00Z","assets":[{"id":3,"name":"a.apk","size":9}]}
        ]"""
        val stable = GitHub.parseReleases(json, includePrerelease = false)!!
        assertEquals("v1", stable.tag)
        assertEquals("First", stable.title)
        assertEquals(listOf(Asset(3, "a.apk", 9)), stable.assets)
        val beta = GitHub.parseReleases(json, includePrerelease = true)!!
        assertEquals("v2-beta", beta.tag)
        assertEquals("v2-beta", beta.title)
        assertEquals("", beta.notes)
        assertNull(GitHub.parseReleases("[]", false))
    }

    @Test fun repoListSurvivesJson() {
        val repos = listOf(RepoEntry("a", "b", Target.WATCH, true), RepoEntry("c", "d"))
        assertEquals(repos, Settings.reposFromJson(Settings.reposToJson(repos)))
    }
}

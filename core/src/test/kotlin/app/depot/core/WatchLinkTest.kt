package app.depot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchLinkTest {
    private val everyField = WatchState(
        apps = listOf(
            WatchApp("me/a"),
            WatchApp("me/b", Status.NotInstalled, latestTag = "v2"),
            WatchApp("me/c", Status.UpdateAvailable, "1.0", "v2", Busy.Downloading(0.29f)),
            WatchApp("me/d", Status.UpdateAvailable, "1.0", "v2", Busy.Installing, needsUser = true),
            WatchApp("me/e", Status.UpToDate, "2.0", "v2", Busy.Checking),
            WatchApp("me/f", Status.NotInstalled, latestTag = "v1", error = "Install cancelled."),
            WatchApp("me/g", Status.NoRelease),
            WatchApp("me/h", Status.NoAsset, latestTag = "v1"),
            WatchApp("me/i", Status.OtherDevice),
        ),
        ack = 6_000_000_000_123L,
    )

    @Test fun stateSurvivesJson() {
        assertEquals(everyField, WatchLink.decodeState(WatchLink.encodeState(everyField)))
        val empty = WatchState(emptyList())
        assertEquals(empty, WatchLink.decodeState(WatchLink.encodeState(empty)))
    }

    @Test fun stateFromAnOlderWatchFillsInWhatIsMissing() {
        // No version, no ack, and apps that carry only some of the fields.
        val state = WatchLink.decodeState("""{"apps":[{"key":"me/a"},{"key":"me/b","status":"up_to_date"}]}""")!!
        assertEquals(0L, state.ack)
        assertEquals(listOf(WatchApp("me/a"), WatchApp("me/b", Status.UpToDate)), state.apps)
        assertEquals(WatchState(emptyList()), WatchLink.decodeState("{}"))
    }

    @Test fun stateFromANewerWatchIgnoresWhatIsUnknown() {
        val json = """{"v":9,"ack":4,"battery":80,"apps":[
          {"key":"me/a","status":"paused","busy":"verifying","percent":50,"channel":"beta","installed":"1.0"},
          {"key":"me/b","status":"not_installed","latest":"v3","confirm":true,"extra":{"x":[1]}},
          {"status":"up_to_date"},
          "not an object"
        ]}"""
        val state = WatchLink.decodeState(json)!!
        assertEquals(4L, state.ack)
        assertEquals(
            listOf(
                // A status or activity this version has no name for is shown as "not checked yet", not as an error.
                WatchApp("me/a", Status.Unknown, installedVersion = "1.0"),
                WatchApp("me/b", Status.NotInstalled, latestTag = "v3", needsUser = true),
            ),
            state.apps,
        )
    }

    @Test fun stateTreatsJsonNullAsMissing() {
        val state = WatchLink.decodeState("""{"apps":[{"key":"me/a","installed":null,"error":null,"busy":null}]}""")!!
        assertEquals(listOf(WatchApp("me/a")), state.apps)
    }

    @Test fun stateThatIsNotJsonIsNull() {
        assertNull(WatchLink.decodeState(null))
        assertNull(WatchLink.decodeState(""))
        assertNull(WatchLink.decodeState("[1,2]"))
    }

    @Test fun percentIsClampedAndKeptWhole() {
        val state = WatchLink.decodeState("""{"apps":[{"key":"a/b","busy":"downloading","percent":250}]}""")!!
        assertEquals(Busy.Downloading(1f), state.apps[0].busy)
        val noPercent = WatchLink.decodeState("""{"apps":[{"key":"a/b","busy":"downloading"}]}""")!!
        assertEquals(Busy.Downloading(0f), noPercent.apps[0].busy)
        for (p in 0..100) assertEquals(p, WatchLink.percent(Busy.Downloading(p / 100f)))
    }

    @Test fun commandsSurviveBytes() {
        val install = WatchCommand.Install("me/app", 77)
        val check = WatchCommand.Check(78)
        assertEquals(install, WatchLink.decodeCommand(WatchLink.encodeCommand(install)))
        assertEquals(check, WatchLink.decodeCommand(WatchLink.encodeCommand(check)))
    }

    @Test fun commandsFromOtherVersions() {
        fun decode(json: String) = WatchLink.decodeCommand(json.toByteArray())
        // Older phone: no id. Newer phone: fields this version does not know.
        assertEquals(WatchCommand.Install("me/app", 0), decode("""{"type":"install","key":"me/app"}"""))
        assertEquals(WatchCommand.Check(5), decode("""{"v":9,"type":"check","id":5,"force":true}"""))
        // A command added later, or one without what it needs, is dropped rather than guessed at.
        assertNull(decode("""{"type":"uninstall","key":"me/app","id":6}"""))
        assertNull(decode("""{"type":"install","id":6}"""))
        assertNull(decode("""{"id":6}"""))
        assertNull(decode("not json"))
        assertNull(WatchLink.decodeCommand(ByteArray(0)))
        assertNull(WatchLink.decodeCommand(null))
    }

    @Test fun buildsStateFromTheAppList() {
        val release = Release("v2", "Two", "", "", emptyList())
        val apps = listOf(
            AppState(
                RepoEntry("me", "a"), "A", "pkg.a", "1.0", release, null, Status.UpdateAvailable,
                busy = Busy.Downloading(0.4567f), error = "x".repeat(900), needsUser = true,
            ),
            AppState(RepoEntry("me", "b"), "B", null, null, null, null, Status.Unknown),
        )
        val state = WatchLink.stateOf(apps, ack = 3)
        assertEquals(3L, state.ack)
        assertEquals(
            WatchApp("me/a", Status.UpdateAvailable, "1.0", "v2", Busy.Downloading(0.46f), "x".repeat(500), true),
            state.app("me/a"),
        )
        assertEquals(WatchApp("me/b"), state.app("me/b"))
        assertNull(state.app("me/c"))
        // What the watch compares before writing must equal what the phone reads back.
        assertEquals(state, WatchLink.decodeState(WatchLink.encodeState(state)))
    }

    @Test fun onlyDownloadProgressIsThrottled() {
        fun state(busy: Busy?, ack: Long = 0, error: String? = null) =
            WatchState(listOf(WatchApp("me/a", Status.NotInstalled, busy = busy, error = error)), ack)

        val at10 = state(Busy.Downloading(0.1f))
        assertTrue(WatchLink.onlyProgressChanged(at10, state(Busy.Downloading(0.2f))))
        // Everything else goes out at once: start, end, a new command, an error, the same state again.
        assertFalse(WatchLink.onlyProgressChanged(state(null), at10))
        assertFalse(WatchLink.onlyProgressChanged(at10, state(Busy.Installing)))
        assertFalse(WatchLink.onlyProgressChanged(at10, state(Busy.Downloading(0.2f), ack = 9)))
        assertFalse(WatchLink.onlyProgressChanged(at10, state(Busy.Downloading(0.2f), error = "No network")))
        assertFalse(WatchLink.onlyProgressChanged(at10, at10))
        assertFalse(WatchLink.onlyProgressChanged(at10, WatchState(emptyList())))
    }

    @Test fun decidesWhichReposGetAWatchLine() {
        fun release(vararg names: String) =
            Release("v1", "", "", "", names.mapIndexed { i, n -> Asset(i + 1L, n, 1) })
        val both = release("app-phone.apk", "app-wear.apk")
        val phoneOnly = release("app.apk", "wear-notes.txt")
        val auto = RepoEntry("me", "a")

        // The setting decides first.
        assertFalse(WatchLink.hasWatchApp(auto.copy(target = Target.PHONE), both, WatchApp("me/a", Status.UpToDate)))
        assertTrue(WatchLink.hasWatchApp(auto.copy(target = Target.WATCH), null, null))

        // Then what the watch found, which knows its own ABIs and installed apps.
        assertTrue(WatchLink.hasWatchApp(auto, phoneOnly, WatchApp("me/a", Status.NotInstalled)))
        assertTrue(WatchLink.hasWatchApp(auto, null, WatchApp("me/a", Status.UpToDate)))
        assertTrue(WatchLink.hasWatchApp(auto, null, WatchApp("me/a", busy = Busy.Installing)))
        assertFalse(WatchLink.hasWatchApp(auto, both, WatchApp("me/a", Status.NoAsset)))

        // Before the watch has checked, the phone goes by the file names it can see.
        assertTrue(WatchLink.hasWatchApp(auto, both, null))
        assertTrue(WatchLink.hasWatchApp(auto.copy(target = Target.BOTH), both, WatchApp("me/a", busy = Busy.Checking)))
        assertFalse(WatchLink.hasWatchApp(auto, phoneOnly, null))
        assertFalse(WatchLink.hasWatchApp(auto, null, WatchApp("me/a")))
    }
}

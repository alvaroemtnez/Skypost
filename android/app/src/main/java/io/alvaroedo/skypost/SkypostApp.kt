package io.alvaroedo.skypost

import android.app.Application
import io.alvaroedo.skypost.core.FakeKeryxCore
import io.alvaroedo.skypost.data.FakeRepository
import io.alvaroedo.skypost.data.FileDemoStore
import io.alvaroedo.skypost.data.Repository
import io.alvaroedo.skypost.location.AndroidLocator
import io.alvaroedo.skypost.location.Locator
import io.alvaroedo.skypost.radio.FakeRadioLink
import io.alvaroedo.skypost.radio.RadioLink
import kotlinx.coroutines.MainScope
import java.io.File

/** Holds the app-wide objects. Phase 2 swaps the fakes for the Rust core and radio. */
class SkypostApp : Application() {
    val radio: RadioLink by lazy { FakeRadioLink() }

    val locator: Locator by lazy { AndroidLocator(this) }

    val repository: Repository by lazy {
        FakeRepository(FakeKeryxCore(), MainScope(), store = FileDemoStore(File(filesDir, "demo-state.json")))
    }
}

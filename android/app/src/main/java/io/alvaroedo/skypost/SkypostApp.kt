package io.alvaroedo.skypost

import android.app.Application
import io.alvaroedo.skypost.core.FakeKeryxCore
import io.alvaroedo.skypost.data.FakeRepository
import io.alvaroedo.skypost.data.Repository
import kotlinx.coroutines.MainScope

/** Holds the app-wide objects. Phase 2 swaps the fakes for the Rust core and radio. */
class SkypostApp : Application() {
    val repository: Repository by lazy { FakeRepository(FakeKeryxCore(), MainScope()) }
}

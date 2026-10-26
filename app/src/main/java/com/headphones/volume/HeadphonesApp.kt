package com.headphones.volume

import android.app.Application
import com.headphones.volume.core.KeyRouter

class HeadphonesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        KeyRouter.init(this)
    }
}

package dev.useunion.sample

import android.app.Application
import dev.useunion.Options
import dev.useunion.PrivacyMode
import dev.useunion.Union

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val options = Options().apply {
            automaticScreenTracking = false // MainActivity tracks its screen explicitly
        }
        Union.configure(this, writeKey = "av_replace_with_your_write_key", privacyMode = PrivacyMode.ProductAnalytics, options = options)
    }
}

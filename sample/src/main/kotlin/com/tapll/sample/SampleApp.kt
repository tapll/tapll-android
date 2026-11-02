package com.tapll.sample

import android.app.Application
import com.tapll.Options
import com.tapll.PrivacyMode
import com.tapll.Tapll

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val options = Options().apply {
            automaticScreenTracking = false // MainActivity tracks its screen explicitly
        }
        Tapll.configure(this, writeKey = "av_replace_with_your_write_key", privacyMode = PrivacyMode.ProductAnalytics, options = options)
    }
}

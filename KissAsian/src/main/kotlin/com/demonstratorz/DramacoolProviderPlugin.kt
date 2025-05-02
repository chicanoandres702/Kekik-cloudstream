package com.lagradost.cloudstream3.providers

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DramacoolProviderPlugin: Plugin() {
    override fun load(context: Context) {
        // Register the CORRECT provider class
        registerMainAPI(DramacoolProvider())
    }
}

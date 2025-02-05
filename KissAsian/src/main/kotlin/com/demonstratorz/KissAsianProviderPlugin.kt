package com.demonstratorz // Update package name if needed

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KissasianProviderPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KissasianProvider())
    }
}
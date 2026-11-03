package com.github.yumeyucca.yumebox.data.trafficv2

import com.tencent.mmkv.MMKV

/** Construct using the existing traffic_statistics MMKV instance, after MMKV.initialize. */
class TrafficUiPreferences(private val storage: MMKV) {
    var range: String
        get() = storage.decodeString("service.range", "TODAY") ?: "TODAY"
        set(value) { storage.encode("service.range", value) }
    var metric: String
        get() = storage.decodeString("service.metric", "DOWNLOAD") ?: "DOWNLOAD"
        set(value) { storage.encode("service.metric", value) }
}

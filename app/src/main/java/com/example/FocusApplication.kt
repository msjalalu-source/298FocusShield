package com.example

import android.app.Application
import android.util.Log
import com.example.service.NewAppBlockStore
import com.example.service.bootstrap.CriticalProtectionBootstrap
import com.example.service.bootstrap.StartupTiming

class FocusApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        StartupTiming.recordProcessStart()

        // Synchronously bootstrap Critical Protection from Device Protected Storage with 0ms DB latency
        CriticalProtectionBootstrap.bootstrap(this)

        // Initialize NewAppBlockStore at application startup so blocked new apps are in memory
        NewAppBlockStore.init(this)

        // Dynamically register PackageReceiver for package lifecycle events (required for API 26+)
        com.example.service.PackageReceiver.register(this)

        Log.i(
            "FocusApplication",
            "⚡ FocusApplication started [PID: ${android.os.Process.myPid()}], Critical Protection Ready: ${CriticalProtectionBootstrap.isControlCenterProtectionReady()}"
        )
    }
}

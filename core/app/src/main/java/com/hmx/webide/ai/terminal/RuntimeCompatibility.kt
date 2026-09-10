package com.hmx.webide.ai.terminal

import android.os.Build
import android.util.Log

/**
 * Runtime compatibility checks for the VibeIDE PRoot-based runtime.
 *
 * VibeIDE's native runtime only supports ARM64 (arm64-v8a) devices.
 * This utility provides clear error messages for unsupported architectures.
 */
object RuntimeCompatibility {

    private const val TAG = "RuntimeCompatibility"

    /** The minimum supported Android API level for PRoot. */
    const val MIN_API_LEVEL = 26 // Android 8.0 (Oreo)

    /** The required CPU ABI. */
    const val REQUIRED_ABI = "arm64-v8a"

    /**
     * Check if the current device is supported by the VibeIDE PRoot runtime.
     *
     * @return Pair of (isSupported, errorMessage). errorMessage is null if supported.
     */
    fun checkCompatibility(): Pair<Boolean, String?> {
        // Check API level
        if (Build.VERSION.SDK_INT < MIN_API_LEVEL) {
            return Pair(false, "VibeIDE runtime requires Android API $MIN_API_LEVEL (Android 8.0) or higher. Current: API ${Build.VERSION.SDK_INT}")
        }

        // Check ABI
        val supportedAbis = Build.SUPPORTED_ABIS
        val hasArm64 = supportedAbis.contains(REQUIRED_ABI) ||
            supportedAbis.any { it.contains("arm64") || it.contains("aarch64") }

        if (!hasArm64) {
            val abis = supportedAbis.joinToString(", ")
            return Pair(false, "VibeIDE runtime requires ARM64 (arm64-v8a) device. Current ABIs: $abis")
        }

        // Check if native libraries can be loaded
        try {
            System.loadLibrary("pocketspawn")
            Log.d(TAG, "Native library pocketspawn loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            return Pair(false, "Failed to load native library 'pocketspawn': ${e.message}")
        }

        return Pair(true, null)
    }

    /**
     * Get a user-friendly description of the current device's architecture.
     */
    fun getDeviceDescription(): String {
        return "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}"
    }
}
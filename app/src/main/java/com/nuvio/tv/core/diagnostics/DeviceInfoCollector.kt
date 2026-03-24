package com.nuvio.tv.core.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceInfoCollector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun collect(): DeviceInfo {
        val packageManager = context.packageManager
        val packageInfo = resolvePackageInfo(packageManager)

        return DeviceInfo(
            packageName = context.packageName,
            appVersionName = packageInfo?.versionName.orEmpty(),
            appVersionCode = packageInfo?.let(PackageInfoCompat::getLongVersionCode) ?: 0L,
            androidVersion = Build.VERSION.RELEASE ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            installerPackageName = resolveInstallerPackageName(packageManager)
        )
    }

    private fun resolvePackageInfo(packageManager: PackageManager) = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(context.packageName, 0)
        }
    }.getOrNull()

    private fun resolveInstallerPackageName(packageManager: PackageManager): String? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

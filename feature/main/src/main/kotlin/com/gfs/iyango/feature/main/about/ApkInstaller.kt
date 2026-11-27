package com.gfs.iyango.feature.main.about

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

sealed interface InstallApkResult {
    data object Launched : InstallApkResult
    data object PermissionRequired : InstallApkResult
    data class Failed(val message: String) : InstallApkResult
}

internal fun installDownloadedApk(context: Context, apkFilePath: String): InstallApkResult {
    val file = File(apkFilePath)
    if (!file.isFile || file.length() <= 0L) {
        return InstallApkResult.Failed("安装包文件不存在，请重新下载")
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        return runCatching {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            InstallApkResult.PermissionRequired
        }.getOrElse { error ->
            InstallApkResult.Failed(error.message ?: "无法打开安装权限设置")
        }
    }
    return runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        InstallApkResult.Launched
    }.getOrElse { error ->
        InstallApkResult.Failed(error.message ?: "无法拉起系统安装器")
    }
}

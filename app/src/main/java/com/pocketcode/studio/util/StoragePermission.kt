package com.pocketcode.studio.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * 「所有文件访问」（MANAGE_EXTERNAL_STORAGE）相关工具。
 *
 * 为什么必须有它：
 *  - App 的工作目录在 /sdcard/PocketCodeStudio/workspaces；
 *  - Android 11（API 30）起，普通存储权限（READ/WRITE_EXTERNAL_STORAGE）被分区存储限制，
 *    无法遍历/读写 /sdcard 下的任意目录，会直接表现为「读不到工作目录内的文件」；
 *  - 唯一解法是申请 MANAGE_EXTERNAL_STORAGE（「所有文件访问」），
 *    而它只能在系统设置页手动授予，无法用运行时弹窗授予。
 *
 * 兼容性：
 *  - API 30+ ：检查 / 跳转 Environment.isExternalStorageManager()；
 *  - API 26~29：退回 READ/WRITE_EXTERNAL_STORAGE 运行时权限。
 */
object StoragePermission {

    /** 是否已具备读写 /sdcard 的完整能力。 */
    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /**
     * 跳转到系统设置里的「所有文件访问」授权页（API 30+）。
     * 部分 ROM 没有“按包名直达”的页面，失败时退回“所有文件访问”总列表页。
     */
    fun requestAccess(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}

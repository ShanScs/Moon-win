package com.muse.moonpc.core

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.win32.StdCallLibrary

/**
 * Windows 11 原生圆角（DWMWA_WINDOW_CORNER_PREFERENCE），参考 Gome PC 实现。
 * 配合 undecorated 窗口使用；不透明窗口，不走透明窗口方案。
 */
object WinRoundedCorners {
    private interface DwmApi : StdCallLibrary {
        fun DwmSetWindowAttribute(
            hwnd: WinDef.HWND,
            dwAttribute: Int,
            pvAttribute: Pointer,
            cbAttribute: Int,
        ): Int
    }

    private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
    private const val DWMWCP_ROUND = 2

    fun apply(hwndLong: Long) {
        try {
            val os = System.getProperty("os.name").lowercase()
            if (!os.contains("win")) return
            val dwm = Native.load("dwmapi", DwmApi::class.java)
            val hwnd = WinDef.HWND(Pointer(hwndLong))
            val attr = com.sun.jna.Memory(4)
            attr.setInt(0, DWMWCP_ROUND)
            dwm.DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, attr, 4)
        } catch (_: Exception) {
            // 忽略：Win10 或调用失败时保持系统默认
        }
    }
}

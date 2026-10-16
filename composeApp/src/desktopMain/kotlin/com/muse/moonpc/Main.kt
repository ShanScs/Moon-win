package com.muse.moonpc

import androidx.compose.ui.window.application
import com.muse.moonpc.ui.MainWindow

fun main() = application {
    MainWindow(onExit = ::exitApplication)
}

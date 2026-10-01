package me.rerere.liquidhub.desktop

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "LiquidHub",
        icon = painterResource("app.png"),
        state = rememberWindowState(width = 1120.dp, height = 760.dp),
    ) {
        App()
    }
}

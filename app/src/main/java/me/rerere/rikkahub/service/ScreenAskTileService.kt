// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// 快捷设置磁贴：一键「问屏幕」——截屏并打开对话页让 AI 分析当前界面。

package me.rerere.rikkahub.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import me.rerere.rikkahub.data.phone.ScreenAskHelper

class ScreenAskTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "问屏幕"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        ScreenAskHelper.askCurrentScreen(this)
    }
}

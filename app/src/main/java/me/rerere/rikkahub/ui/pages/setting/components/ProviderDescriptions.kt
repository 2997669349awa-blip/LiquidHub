// Modified by AI Hello World on 2026-10-01.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.
// Provider 的描述 UI 从数据模型里拆出来，放到 UI 层（模型现在与平台无关，位于 :core）。

package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.LOCAL_AI_PROVIDER_ID
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import kotlin.uuid.Uuid

private val AIHUBMIX_ID = Uuid.parse("1b1395ed-b702-4aeb-8bc1-b681c4456953")
private val APIMART_ID = Uuid.parse("2a05506f-3a59-450a-a493-33a82bc85a81")
private val SILICONFLOW_ID = Uuid.parse("56a94d29-c88b-41c5-8e09-38a7612d6cf8")
private val AI302_ID = Uuid.parse("da93779f-3956-48cc-82ef-67bb482eaaf7")
private val SUIXIANG_ID = Uuid.parse("aecf04fd-cb5c-4582-aed2-e8bf393923fd")
private val MARUCODE_ID = Uuid.parse("afbc54ad-807e-4455-9594-7d7a546356ad")

@Composable
fun providerDescription(provider: ProviderSetting) {
    when (provider.id) {
        AIHUBMIX_ID -> Text(
            text = buildAnnotatedString {
                append("提供 OpenAI、Claude、Google Gemini 等主流模型的高并发和稳定服务")
                appendLine()
                append("官网：")
                withLink(LinkAnnotation.Url("https://aihubmix.com?aff=pG7r")) {
                    withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                        append("https://aihubmix.com")
                    }
                }
                appendLine()
                append("充值: ")
                withLink(LinkAnnotation.Url("https://console.aihubmix.com/topup")) {
                    withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                        append("https://console.aihubmix.com/topup")
                    }
                }
            }
        )

        APIMART_ID -> Text(
            text = buildAnnotatedString {
                append("APIMart 是专注 AI 图片/视频生成的低价 API 平台，GPT-Image-2 低至 $0.006/张，1 美元可出图 160+ 张。图片、视频一套异步 API 通吃，提交任务拿 ID、回调取结果，跑批万张不超时、换模型不改代码。按量付费、无月费。")
                appendLine()
                withLink(LinkAnnotation.Url("https://go.apimart.ai/gh-rikkahub")) {
                    withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                        append("通过此注册链接注册即可开用")
                    }
                }
            }
        )

        SILICONFLOW_ID -> MarkdownBlock(
            content = """
                ${stringResource(R.string.silicon_flow_description)}
                ${stringResource(R.string.silicon_flow_website)}
            """.trimIndent()
        )

        AI302_ID -> Text(
            text = buildAnnotatedString {
                append("企业级AI服务, 官网：")
                withLink(LinkAnnotation.Url("https://302.ai/")) {
                    withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                        append("https://302.ai/")
                    }
                }
            }
        )

        SUIXIANG_ID -> Text(
            text = buildAnnotatedString {
                append("可靠高效的 API 中继服务，提供 Claude、Codex、Gemini 等中继服务。注重隐私·无数据倒卖·无模型掺水，充值额度 1:1，按量付费。多线路冗余、跨区域容灾、自动故障切换，长链路 SSE 不中断。\n")
                append("官网：")
                withLink(LinkAnnotation.Url("https://sui-xiang.com")) {
                    withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                        append("https://sui-xiang.com")
                    }
                }
            }
        )

        MARUCODE_ID -> Text(
            text = buildAnnotatedString {
                append("MaruCode 是一家偶尔做做慈善的小破站 API，自营号池，主要提供 Codex、Claude Code、GPT Image 等主流模型，支持 Websocket 协议，明码标价(Codex 0.25x, CC 1.5x)，透明汇率(1:1)。")
                appendLine()
                withLink(LinkAnnotation.Url("https://api.muteki.site/register?aff=Rikkahub&promo=Rikkahub")) {
                    withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                        append("新用户注册送 2 刀")
                    }
                }
                appendLine()
                withLink(LinkAnnotation.Url("https://images-2.muteki.site")) {
                    withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                        append("生图工作台🖼️")
                    }
                }
            }
        )

        LOCAL_AI_PROVIDER_ID -> Text(
            text = "在终端中的likkahub本地AI无需联网就可以调用。\n" +
                "先在 Termux 里运行：pkg install nodejs ollama，再 npm i -g likkahub，然后用 likkahub go 在终端中对话。\n" +
                "终端服务启动后，本提供商即可直接调用其中的 Ollama 模型（默认地址 http://127.0.0.1:11434/v1）。"
        )

        else -> {}
    }
}

@Composable
fun providerShortDescription(provider: ProviderSetting) {
    when (provider.id) {
        AIHUBMIX_ID -> Text("支持gpt, claude, gemini等200+模型")
        APIMART_ID -> Text("AI 图片/视频生成，GPT-Image-2 低至 $0.006/张")
        SUIXIANG_ID -> Text("Claude、Codex、Gemini 等中继服务，1:1 充值")
        LOCAL_AI_PROVIDER_ID -> Text("在终端中的likkahub本地AI无需联网就可以调用")
        else -> {}
    }
}

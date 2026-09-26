# likkahub CLI

在手机终端（Termux）里直接使用本地模型与云端模型的命令行客户端。装上它就不再需要安装 Android App。

## 安装

```sh
curl -sL https://2997669349awa-blip.github.io/LiquidHub/install.sh | sh
```

脚本会执行 `pkg install nodejs ollama` 并全局安装 `likkahub`。也可以手动：

```sh
pkg install nodejs ollama
npm install -g https://2997669349awa-blip.github.io/LiquidHub/likkahub-cli.tgz
likkahub init
```

## 使用

```sh
likkahub model pull qwen2.5:1.5b   # 从 Ollama 拉取本地模型
likkahub go                        # 进入对话（服务日志静默）
likkahub log                       # 需要时实时查看本地AI服务日志
```

常用命令：

| 命令 | 说明 |
| --- | --- |
| `likkahub init [--install]` | 初始化；`--install` 自动装依赖 |
| `likkahub go` | 进入对话 |
| `likkahub start / stop / status` | 管理本地 Ollama 服务 |
| `likkahub model list` | 列出本地模型 |
| `likkahub model pull <名称>` | 拉取模型 |
| `likkahub model rm <名称>` | 删除模型 |
| `likkahub provider add <id> <baseUrl> [apiKey]` | 添加 OpenAI 兼容云端 provider |
| `likkahub provider key <id> <apiKey>` | 设置 provider 密钥 |
| `likkahub provider model <id> <模型名>` | 为 provider 添加模型 |
| `likkahub log` | 实时查看本地AI服务日志 |

对话内命令：`/model <名称>`、`/models`、`/clear`、`/exit`。

模型标识格式：本地用 `qwen2.5:1.5b` 或 `ollama:qwen2.5:1.5b`，云端用 `openai:gpt-4o-mini`。

## 说明

- 本地推理由 Termux 的 `ollama` 包提供，默认监听 `127.0.0.1:11434`。
- 对话时 `ollama serve` 的日志会写入 `~/.local/state/likahub/ollama.log`，界面保持干净；用 `likkahub log` 查看。
- 云端 provider 配置保存在 `~/.config/likahub/config.json`。
- Android 上其他 App（例如 LiquidHub）可以直接访问 `127.0.0.1:11434`，从而复用这里拉取的模型。

## 分发（维护者）

```sh
cd cli
npm pack                       # 生成 likkahub-<version>.tgz
# 重命名为 likkahub-cli.tgz 并发布到站点根目录
```

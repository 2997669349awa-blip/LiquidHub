# LiquidHub

一个以液态玻璃风格重做的 Android LLM 聊天客户端，基于 RikkaHub 二次开发。

它会聊天、会搜歌放歌、会自己在手机里搭一个能跑命令的 Linux 工作区、还能在你授权后真的替你操作手机。

下载：

- 站点与更新：https://2997669349awa-blip.github.io/LiquidHub/
- 最新安装包：https://2997669349awa-blip.github.io/LiquidHub/liquidhub-universal-release.apk
- 源码：https://github.com/2997669349awa-blip/LiquidHub

---

## 这是什么

LiquidHub 不是简单换皮的 RikkaHub 分支。它在保留上游全部能力的前提下，重新做了一层视觉，并且补上了几块上游没有、但日常很想用的东西：音乐、远程桌面、手机控制、脚本音乐源、对话框命令。

它和上游的关系：上游是「一个功能齐全的 LLM 客户端」，LiquidHub 想做的是「一个能真正替你干事的随身助手」。多出来的功能都围绕「让 AI 能在你的设备上动起来」这个方向。

---

## 亮点

### 音乐

内置一个完整的播放器：搜索、播放、通知栏控制、歌词原文加翻译、全屏黑胶播放、喜欢、扫码或手机号登录。

关键的一点：本应用不内置任何第三方音乐接口，也不附带任何账号信息。音乐能力由一个可插拔的「音乐源」提供，由你自己添加。

### 音乐源插件

进入 设置，其他，音乐源，添加一个音乐源，写一个名称，然后导入 `manifest.json` 和 `index.js`，测试通过即可启用。启用后，音乐页和 AI 搜歌都会走你添加的音乐源；没有启用的音乐源时，音乐功能不会联网，也不会自带任何接口。

插件运行在内置的脚本引擎里，脚本里可以通过我们提供的联网接口自己请求数据。写法和示例见下方。

### 手机控制

授权之后，AI 可以真正操作手机：返回桌面、打开应用、点击、滑动、输入文字、返回、截图、读取屏幕内容，以及自动刷短视频这类循环。开始控制时右上角会出现一个悬浮球，实时显示 AI 的思考和当前动作，单击悬浮球停止，控制结束自动回到应用。授权方式支持外部权限工具、无障碍和悬浮窗。

### 工作区与本地执行

工作区是一个跑在手机本地的精简 Linux 环境，AI 可以在里面读写文件、执行命令、联网。属于你的文件可以选择保留到公共目录，卸载重装不丢。

### 远程桌面

工作区里可以装一个图形桌面，装好后给 AI 使用，可以截图、点击、打字、看网页。

### 对话框命令

在输入框输入以双斜杠开头的命令即可本地执行，不会发给 AI。可以查看任务、停止任务、恢复任务、查看状态。命令大小写不敏感，任务可以用任务编号或会话编号定位。完整清单见 设置，其他，命令指南。

### 液态玻璃视觉

Haze 背景模糊叠加多层渐变与边缘高光，支持毛玻璃与光学折射两种效果，可在设置里切换。

---

## 下载与安装

从上面的链接下载安装包，允许「安装未知来源应用」后安装即可。当前仅提供 Android 版本。

已安装旧版时，直接覆盖安装（包名一致）即可。

---

## 更新

应用内更新会同时检测正式版和预览版，优先推正式版，正式版没有更新时再看预览版。

更新信息来自本站的 `updates.json`。

---

## 音乐源插件写法

`manifest.json`：

```json
{
  "name": "MySource",
  "version": "1.0.0",
  "type": "music",
  "main": "index.js"
}
```

`index.js` 需要定义三个全局函数。联网请使用注入的 `__http(method, url, headersJson, body)`，它返回响应文本。

```js
function search(keyword) {
  var res = __http("GET", "https://your-api/search?q=" + encodeURIComponent(keyword), "{}", "");
  return JSON.stringify(JSON.parse(res).songs.map(function (s) {
    return {
      id: String(s.id),
      name: s.name,
      artist: s.artist,
      album: s.album,
      cover: s.cover
    };
  }));
}

function songUrl(id) {
  return JSON.parse(__http("GET", "https://your-api/url?id=" + id, "{}", "")).url;
}

function lyrics(id) {
  return JSON.parse(__http("GET", "https://your-api/lyric?id=" + id, "{}", "")).lrc || "";
}
```

说明：脚本会运行在你的设备上，`__http` 允许它联网，请只添加你信任的音乐源。

---

## 免责声明

本项目不内置、不附带任何第三方音乐接口、账号或密钥。所有音乐能力均由使用者自行添加的音乐源提供。使用者需自行确保其添加的内容来源合法，并自行承担相应责任。

本项目基于 RikkaHub 二次开发，遵循 AGPL-3.0。

---

## 构建

```bash
./gradlew :app:assembleDebug
```

需要 JDK 17、Android SDK 与 NDK。首次构建会下载依赖与 NDK，耗时较长。

---

## 许可与致谢

本项目是 RikkaHub 的衍生作品，遵循 GNU Affero General Public License v3.0（AGPL-3.0）。上游版权归 RikkaHub 作者所有，详见 `LICENSE` 与各文件的修改声明。

感谢 RikkaHub 提供了扎实的基础。

欢迎在 RikkaHub 论坛与交流群里反馈问题、提建议。

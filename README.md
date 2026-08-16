# Android MCP

把安卓手机变成一台 **MCP（Model Context Protocol）服务器**：任何兼容 MCP 的 Agent（Kimi Code、Claude Code、Cursor、自研 Agent……）连接后即可查看屏幕、点击滑动、读通知、发短信、执行 shell——约 50 个工具，全部带独立的权限开关与 API Key 鉴权。

UI 采用 Gemini 视觉体系（深邃底色 + 蓝→紫→粉能量渐变），工具被调用时屏幕边缘绽放彩色粒子、底部毛玻璃胶囊滚动显示调用实况，Android 16+ 还有 Live Update 状态栏胶囊。

## 功能总览

- **MCP 服务**：官方 Kotlin SDK + Ktor，Streamable HTTP 端点 `http://<手机IP>:8080/mcp`
- **API Key 鉴权**：首启自动创建 `default` key（明文只显示一次，可扫码分享连接配置）；支持多 key、命名、禁用、撤销
- **工具级权限管控**：每个工具独立开关；敏感工具（短信/通讯录/通话/剪贴板/shell）默认关闭；Android 运行时权限未授予时返回明确错误并可在 App 内一键跳转授权
- **无障碍模式**：读 UI 树（紧凑 JSON）、截图、点击/滑动/输入/全局按键、等待元素——无需 root
- **实时反馈**：Live Update 通知（API 36+ Promoted Ongoing）、边缘粒子光效（按工具类别着色）、底部模糊胶囊
- **远程隧道**：内置 cloudflared（quick / named tunnel）与 frpc（自建 frps），二进制已随 APK 打包（按 ABI 释放 + SHA256 校验），公网 Agent 也能连
- **Shizuku（可选）**：`run_shell` / `logcat` / `settings` / `pm` 等 shell 级工具，等同 adb shell 权限

## 快速开始

1. 安装 App，打开即自动创建 `default` API Key（弹窗里扫码或复制保存）。
2. 点「启动服务」，记下连接地址（如 `http://192.168.1.5:8080/mcp`）。
3. 在 Agent 侧配置（以通用 `mcpServers` 格式为例）：

```json
{
  "mcpServers": {
    "android-phone": {
      "type": "http",
      "url": "http://192.168.1.5:8080/mcp",
      "headers": { "Authorization": "Bearer amcp_xxxx" }
    }
  }
}
```

- **USB 场景**：`adb forward tcp:8080 tcp:8080`，然后连 `http://127.0.0.1:8080/mcp`
- **调试**：`npx -y @modelcontextprotocol/inspector` 连同一地址（CORS 已开）

要使用屏幕操作类工具，在系统设置中为本应用开启**无障碍服务**；通信类工具在「工具」页逐个启用并授予运行时权限。

## 工具清单（节选）

| 分组 | 工具 |
|---|---|
| 屏幕与输入（无障碍） | `screenshot` `get_ui_tree` `find_element` `click` `long_click` `input_text` `swipe` `scroll` `gesture` `key_event` `global_action` `wait_for` `get_current_app` |
| 系统控制 | `get/set_volume` `get/set_brightness` `ringer_mode` `dnd` `flashlight` `vibrate` `wake_screen` `list_apps` `launch_app` `open_url` `open_app_settings` `set_alarm` `set_timer` `toast` `speak` `set_clipboard` |
| 读取与感知 | `get_device_info` `get_battery` `get_network_info` `get_clipboard`※ `get_location`※ `list_sensors` `read_sensor` `now_playing` |
| 文件 | `list_files` `read_file` `write_file` `delete_file`（限制在应用私有目录，Download 只读） |
| 通信※（默认关闭） | `read_notifications` `dismiss_notification` `send_sms` `list_sms` `read_call_log` `query_contacts` `make_call` `media_control` |
| Shizuku※ | `run_shell` `get_logcat` `settings_get` `settings_put` `pm_command` |

※ 敏感工具默认关闭，需在「工具」页显式启用。

## 架构

```
app/src/main/java/dev/androidmcp/
 ├─ server/          McpServerService(前台服务) + McpServerManager(Ktor CIO + Bearer 鉴权)
 ├─ auth/            ApiKeyStore（SecureRandom 32B，SHA-256 存储，明文只显一次）
 ├─ tools/           McpTool 接口 + Schema DSL + ToolRegistry（开关/权限/事件/计时管线）
 ├─ tools/impl/      五组工具实现（Basic / A11y / System / File / Communication / Shizuku）
 ├─ accessibility/   A11yService：UI 树序列化、手势、截图、元素查找、等待
 ├─ effects/         EffectOverlayService：边缘粒子 + 底部模糊胶囊（优先无障碍悬浮窗，免悬浮窗权限）
 ├─ tunnel/          cloudflared / frpc：内置二进制释放校验 + 子进程托管 + 日志
 ├─ events/          ToolCallEventBus：驱动动态页、通知与特效
 └─ ui/              Compose M3（Gemini 深色主题）：服务/密钥/工具/动态/设置/隧道
```

## 构建

```bash
./gradlew assembleDebug   # JDK 17（gradle.properties 已指定 org.gradle.java.home）
```

工具链：AGP 9.3.1 · Gradle 9.7 · Kotlin 2.3.21 · compileSdk/targetSdk 36 · minSdk 31 · MCP Kotlin SDK 0.15.0 · Ktor 3.5.2 · Hilt 2.60.1。

## 安全说明

- 所有 `/mcp` 请求必须携带有效 `Authorization: Bearer` 头，否则 401。
- 开启隧道等于把服务暴露到公网，请务必保管好 API Key，不用时及时撤销。
- `run_shell` 等同把 adb shell 交给 Agent，请只在可信网络/可信 Agent 下启用。
- 边缘特效优先复用无障碍悬浮窗（免额外权限）；无障碍未开启时需要「显示在其他应用上」权限。

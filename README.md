# OpenCode Client (Android)

非官方的 [OpenCode](https://opencode.ai) 安卓客户端。参考官方 Web 端（仓库内 `packages/app`）的交互与 `@opencode/client` 的调用方式，通过 OpenCode 的 HTTP API 与服务器通信。

- 技术栈：Kotlin + Jetpack Compose (Material 3) + OkHttp(SSE) + DataStore
- 架构：纯客户端。服务器由 `opencode serve` 提供（可跑在本机 Termux、局域网电脑或远程 VPS）
- 编译：GitHub Actions 云端构建 release 包（R8 混淆）

## 服务器准备

```sh
# 不设密码（仅本机使用）
opencode serve --port 4096

# 或设置访问密码
OPENCODE_PASSWORD=你的密码 opencode serve --port 4096
```

App 内服务器地址填 `http://127.0.0.1:4096`（安卓上各应用共享网络栈，回环地址互通）。

## 功能

- 会话列表、新建/删除会话，标题点击可快速切换对话
- 消息流式渲染：正文 Markdown（代码块染色）、思考过程折叠并显示耗时
- 工具调用卡片：状态/耗时/增删统计，edit 类工具直接展示 diff，shell 输出可复制
- 上下文用量指示器：当前上下文占用与模型上限百分比，展开可看累计用量
- Agent / 模型 / 思考档位切换
- 附件：图片与文件上传至服务器临时目录后按路径引用（与 Web 端一致）
- 斜杠命令（含内置 `/compact`）、生成中可中断、输入草稿按会话保存
- 深色主题（可切换日间）

## 接口依据

- OpenAPI 规范：https://opencode.ai/v2/openapi.json
- 客户端文档：https://opencode.ai/v2/docs/build/client
- 认证：Basic Auth（用户名 `opencode`，密码为 `OPENCODE_PASSWORD`）

## 免责声明

本项目为非官方第三方客户端，与 OpenCode 官方无任何关联，未获其背书或支持。

软件按「现状」提供，不附带任何明示或暗示的担保，包括但不限于适销性、特定用途适用性及不侵权担保。使用本软件所产生的任何风险与后果（包括数据丢失、服务中断、API 或模型费用、账号封禁等）均由使用者自行承担，作者不承担任何责任。

请自行妥善保护服务器地址、访问密码及会话内容；上传至服务器的文件会写入服务器端临时目录，请勿传输敏感信息。

## License

MIT

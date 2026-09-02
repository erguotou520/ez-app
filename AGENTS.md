# AGENTS.md

## 项目

Ez App 是包含 Android 工具箱、macOS 菜单栏应用和 fcitx5 Android 输入法的
monorepo。剪切板同步协议由三端共同实现。

## 工作要求

- 编码前列出必要假设；遇到会改变产品行为的歧义时先说明。
- 用最少的代码实现需求，不重构无关代码。
- 每一行修改都应能追溯到当前任务；保留用户已有的未提交改动。
- 不使用 `gh`；GitHub 仓库操作使用 `git` 和 SSH。
- 不提交密钥、密码、`.jks`、APK、构建目录或设备数据。

## 三端一致性

- 局域网发现服务为 `_ezclip._tcp.`，默认端口为 `42424`。
- MQTT 默认地址为 `b01a87f3.ala.cn-hangzhou.emqxsl.cn:8883`，仅作为可编辑默认值。
- MQTT Topic、AES-GCM/HKDF 参数、消息 ID、时间戳和去重规则不可单端修改。
- 局域网连接优先；断开后启用 MQTT，局域网恢复时停用 MQTT。
- 收藏历史置顶且永久保留；非收藏历史按时间倒序最多 100 条，清除操作保留收藏。

## 子项目验证

- Android 工具箱：`cd android-toolbox && ./gradlew :app:assembleDebug`
- macOS 剪切板：`cd macos-clipboard-bridge && swift build -c release`
- 输入法：`cd android-fcitx5-input-method && BUILD_ABI=arm64-v8a ./gradlew :app:assembleDebug`
- 提交前：`git diff --check`

报告结果时分别说明源码检查、构建、APK 签名、安装和真机测试的证据等级。

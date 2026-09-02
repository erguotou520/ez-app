# CLAUDE.md

本文件为在本仓库中工作的编码助手提供上下文。

## 仓库边界

- `android-toolbox/`：Kotlin、Jetpack Compose Android 工具箱。
- `android-fcitx5-input-method/`：基于 fcitx5-android 的大型 Kotlin/C++ 工程。
- `macos-clipboard-bridge/`：Swift Package、AppKit 菜单栏应用。
- `macos-file-provider/`：macOS File Provider 扩展与远程存储适配。
- 三个应用独立构建。不要为局部需求引入跨工程构建耦合。

## 关键约束

- 保持局域网优先、MQTT 回退；局域网恢复后应停止 MQTT。
- 三端的 MQTT Topic、加密、时间排序、去重和历史策略必须保持兼容。
- MQTT 地址默认值为 `b01a87f3.ala.cn-hangzhou.emqxsl.cn:8883`，但必须允许用户修改。
- 普通历史最多保留 100 条；收藏项置顶、不被“清除所有历史”删除且不计入 100 条。
- 不提交 `.jks`、密码、私钥、MQTT 凭据或生成的 APK。
- 不改动与需求无关的 fcitx5 上游代码、格式和许可证。

## 修改原则

1. 先定位真实运行路径，明确假设和可验证的成功标准。
2. 只修改需求直接涉及的文件，沿用各子项目现有风格。
3. 协议字段发生变化时，必须同时检查 Android、macOS 和输入法。
4. UI 文案使用简体中文，保持简单、清晰、触控友好。
5. 区分静态检查、编译成功、安装成功和真机行为，不把前者表述成后者。

## 验证

```bash
cd android-toolbox && ./gradlew :app:assembleDebug
cd macos-clipboard-bridge && swift build -c release
cd android-fcitx5-input-method && BUILD_ABI=arm64-v8a ./gradlew :app:assembleDebug
git diff --check
```

输入法原生构建依赖 Android NDK、CMake、Extra CMake Modules、Ninja 和 Gettext。
正式 APK 必须使用 CI Secrets 或本机安全保存的 keystore 签名。

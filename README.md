# Ez App Monorepo

`ez-app` 使用一个 Git 仓库管理 Android 工具箱、macOS 剪切板桥接端和输入法。
三个应用保持独立构建，不使用 Git Submodule。

## 目录

| 目录 | 内容 |
| --- | --- |
| `android/` | 拾光 Android 工具箱：扫码、网络诊断、剪切板同步 |
| `macos/` | 拾光 macOS 菜单栏剪切板应用 |
| `input-method/` | 基于 fcitx5-android 的输入法源码 |

## 构建

### Android 工具箱

```bash
cd android
./gradlew :app:assembleDebug
```

### macOS

```bash
cd macos
swift build -c release
```

### 输入法

```bash
cd input-method
./gradlew :app:assembleDebug
```

输入法包含原 fcitx5-android 的 C++ 引擎、插件和词库源码。上游仓库及子模块的
Git 元数据已移除，源码统一由本仓库管理；各上游组件的许可证文件保持原样。

## 输入法后续改造

在保留 fcitx5-android 原有输入能力的基础上，为输入法增加拾光剪切板同步：

- 默认输入法运行时自动捕获其他应用复制的文本；
- 与 Android 工具箱、macOS 端使用相同的局域网优先和 MQTT 回退策略；
- 复用相同的 MQTT Topic、AES-GCM 加密协议、时间顺序和去重规则；
- 提供账号密码设置、同步开关和保留收藏项的共享历史；
- Android 工具箱继续保留，输入法作为可选的自动同步方案。


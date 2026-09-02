# Ez App

Ez App 是一个面向 Android 与 macOS 的轻量工具集合。仓库采用 monorepo：
各应用独立构建、独立发布，但共享剪切板同步协议和产品设计。

## 项目结构

| 目录 | 应用 | 主要能力 |
| --- | --- | --- |
| `android-toolbox/` | 拾光 Android 工具箱 | 扫码、网络诊断、剪切板同步、快捷设置入口 |
| `android-fcitx5-input-method/` | 拾光输入法 | fcitx5 输入能力、系统剪切板监听、同步历史与收藏 |
| `macos-clipboard-bridge/` | 拾光 macOS 菜单栏应用 | 剪切板监听、同步状态、历史与收藏 |
| `macos-file-provider/` | macOS 文件提供器 | 文件提供器扩展与远程存储适配 |

输入法基于 fcitx5-android 源码改造。上游 Git 元数据已移除，各上游许可证保留在
源码树中。

## 剪切板同步

- 同一局域网内优先自动发现并直连，局域网不可用时回退到 MQTT over TLS。
- 局域网恢复后停止 MQTT 通道并切回局域网。
- 三端使用同一 MQTT 用户名作为频道标识，内容在发送前进行 AES-GCM 加密。
- MQTT 默认服务器为 `b01a87f3.ala.cn-hangzhou.emqxsl.cn:8883`，三端均可在设置中修改。
- 默认采用时间较新的剪切板内容；历史中收藏项置顶且不计入普通历史的 100 条上限。
- Android 10 及以上限制后台应用读取剪切板。工具箱需手动同步；启用输入法后可由输入法
  在其进程存活且系统允许时监听。

## 本地构建

需要 Android SDK、JDK 17 和 Xcode Command Line Tools。输入法还需要 NDK、CMake、
Extra CMake Modules、Ninja 和 Gettext。

```bash
# Android 工具箱
cd android-toolbox
./gradlew :app:assembleDebug

# macOS 菜单栏应用
cd macos-clipboard-bridge
swift build -c release

# 输入法（仅构建真机常用的 arm64）
cd android-fcitx5-input-method
BUILD_ABI=arm64-v8a ./gradlew :app:assembleDebug
```

## GitHub Actions 与正式签名

[Android APK 工作流](.github/workflows/android-release.yml)在 PR 中构建 Debug APK；
推送到 `main`、推送 `v*` 标签或手动运行时构建签名 Release APK。Android 工具箱和输入法
共用同一个正式证书，构建产物可在对应 Actions 运行的 Artifacts 中下载。

仓库只保存构建逻辑和公钥信息，不提交 `.jks` 私钥文件或密码。请在仓库
`Settings → Secrets and variables → Actions` 中配置：

| Secret | 内容 |
| --- | --- |
| `ANDROID_RELEASE_KEYSTORE_BASE64` | `.jks` 文件的 Base64 单行文本 |
| `ANDROID_RELEASE_KEYSTORE_PASSWORD` | keystore 密码；当前构建也将其用作 key 密码 |
| `ANDROID_RELEASE_KEY_ALIAS` | 签名条目的 alias |

在 macOS 上生成 Base64：

```bash
base64 -i /path/to/release.jks | tr -d '\n'
```

`.jks` 应离线备份在受控位置。证书丢失会影响已有应用升级；若公开仓库，应只公开
证书指纹或导出的公钥证书，绝不能公开私钥 keystore 和密码。

本地 Release 构建可使用同样的环境变量：

```bash
SIGN_KEY_FILE=/absolute/path/release.jks \
SIGN_KEY_PWD='your-password' \
SIGN_KEY_ALIAS='your-alias' \
./gradlew :app:assembleRelease
```

## 配置与数据

- Android 工具箱和输入法：在各自的“远程同步设置”中配置 MQTT 地址、TLS 端口、
  用户名和密码。
- macOS：菜单栏选择“远程同步设置…”；配置保存在
  `~/.ez-clipboard/config.json`，历史保存在 `~/.ez-clipboard/history.json`。
- MQTT 使用 TLS 连接。若改用私有 CA 证书，需要另行加入对应平台的信任链；默认服务
  使用系统可验证的证书时无需手工导入。

## 安全说明

MQTT 账号用于鉴权和派生用户频道；剪切板正文另行端到端加密。不要在日志、Issue、
提交记录或截图中暴露 MQTT 密码、keystore 密码和私钥文件。更换 MQTT 地址不会改变
同步协议，但双方必须配置相同的服务和账号。

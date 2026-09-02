# Ez Remote Drive

原生 macOS 远程文件管理器。每个 SMB、WebDAV、S3 或 SFTP 数据源会注册成一个
File Provider domain，并显示在 Finder 的“位置”中。

## 架构

- `EzRemoteDrive`：SwiftUI 主应用，管理数据源、浏览远端目录、审核本地变更。
- `EzRemoteFileProvider`：Finder File Provider Extension，枚举占位符并按需下载。
- `Shared`：App Group 中共享的配置、缓存索引和待同步变更。
- `Remote`：纯 Swift 数据源协议与 WebDAV、S3、SFTP、SMB adapter。

Finder 中的修改先进入待同步队列。主应用内勾选变更并点击“同步所选”后才会写回远端；
删除项还必须额外勾选“同步删除远端文件”。

## 构建

```bash
./build.sh
```

构建产物位于 `build/EzRemoteDrive.app`。默认使用不含 App Group 的临时签名，适合验证
应用界面和应用内协议操作；File Provider 与主应用共享配置仍需要正式开发者签名。
要让 Finder 稳定加载扩展，需要在 `Config.xcconfig` 中设置自己的 Team ID，并在 Apple
Developer 账户中为主应用和扩展启用 App Groups：

```text
group.com.erguotou.ezremote
```

然后用对应证书签名并将应用放入 `/Applications`。首次使用时需在
“系统设置 → 通用 → 登录项与扩展 → 文件提供程序”中启用扩展。

## 数据源说明

- WebDAV：使用 `URLSession` 的 PROPFIND/GET/PUT/MKCOL/MOVE/DELETE。
- S3：使用兼容 S3 的 HTTPS API；支持 AWS、MinIO、R2 等 endpoint。
- SFTP：Swift adapter 调用 macOS 自带 `/usr/bin/sftp`，使用 SSH config 或密钥认证。
- SMB：Swift adapter 使用 macOS 自带 SMB 挂载能力；当前支持匿名共享，密码不会进入进程参数。

凭据仅保存在 Keychain，App Group 配置中只保存 Keychain account 标识。

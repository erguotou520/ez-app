import Foundation
import NetFS
import Darwin

final class CommandStorage: RemoteStorage, @unchecked Sendable {
    enum Mode { case sftp, smb }
    private let source: DataSource
    private let password: String
    private let mode: Mode

    init(source: DataSource, password: String, mode: Mode) {
        self.source = source
        self.password = password
        self.mode = mode
    }

    func list(path: String) async throws -> [RemoteItem] {
        switch mode {
        case .sftp:
            return parseSFTPList(try await sftp("ls -la \(quoted(remotePath(path)))"), parent: path)
        case .smb:
            if path == "/", configuredSMBShare == nil {
                return try await listSMBShares()
            }
            return try await withSMBMount(path: path) { root, relativePath in
                try FileManager.default.contentsOfDirectory(
                    at: root.appendingPathComponent(relativePath),
                    includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey],
                    options: [.skipsHiddenFiles]
                ).map { try self.localItem($0, parent: path) }
            }
        }
    }

    func stat(path: String) async throws -> RemoteItem {
        switch mode {
        case .sftp:
            let parent = (path as NSString).deletingLastPathComponent
            let name = (path as NSString).lastPathComponent
            guard let item = parseSFTPList(
                try await sftp("ls -la \(quoted(remotePath(parent)))"),
                parent: parent
            ).first(where: { $0.name == name }) else { throw RemoteError.server(404, "SFTP 文件不存在") }
            return item
        case .smb:
            if configuredSMBShare == nil,
               path.split(separator: "/", omittingEmptySubsequences: true).count == 1 {
                return RemoteItem(name: (path as NSString).lastPathComponent, path: path,
                                  isDirectory: true, size: 0, modifiedAt: nil, version: path)
            }
            return try await withSMBMount(path: path) { root, relativePath in
                try self.localItem(root.appendingPathComponent(relativePath),
                                   parent: (path as NSString).deletingLastPathComponent)
            }
        }
    }

    func download(path: String, to localURL: URL) async throws {
        switch mode {
        case .sftp:
            _ = try await sftp("get \(quoted(remotePath(path))) \(quoted(localURL.path))")
        case .smb:
            try await withSMBMount(path: path) { root, relativePath in
                try FileManager.default.copyItem(at: root.appendingPathComponent(relativePath), to: localURL)
            }
        }
    }

    func upload(localURL: URL, to path: String) async throws {
        switch mode {
        case .sftp:
            _ = try await sftp("put \(quoted(localURL.path)) \(quoted(remotePath(path)))")
        case .smb:
            try await withSMBMount(path: path) { root, relativePath in
                let target = root.appendingPathComponent(relativePath)
                if FileManager.default.fileExists(atPath: target.path) { try FileManager.default.removeItem(at: target) }
                try FileManager.default.copyItem(at: localURL, to: target)
            }
        }
    }

    func createDirectory(path: String) async throws {
        switch mode {
        case .sftp: _ = try await sftp("mkdir \(quoted(remotePath(path)))")
        case .smb:
            try await withSMBMount(path: path) { root, relativePath in
                try FileManager.default.createDirectory(at: root.appendingPathComponent(relativePath),
                                                        withIntermediateDirectories: false)
            }
        }
    }

    func move(from: String, to: String) async throws {
        switch mode {
        case .sftp: _ = try await sftp("rename \(quoted(remotePath(from))) \(quoted(remotePath(to)))")
        case .smb:
            let fromTarget = try smbTarget(path: from)
            let toTarget = try smbTarget(path: to)
            guard fromTarget.share == toTarget.share else {
                throw RemoteError.unsupported("暂不支持跨 SMB 共享移动文件")
            }
            try await withSMBMount(path: from) { root, relativePath in
                try FileManager.default.moveItem(at: root.appendingPathComponent(relativePath),
                                                 to: root.appendingPathComponent(toTarget.relativePath))
            }
        }
    }

    func delete(path: String) async throws {
        switch mode {
        case .sftp:
            do { _ = try await sftp("rm \(quoted(remotePath(path)))") }
            catch { _ = try await sftp("rmdir \(quoted(remotePath(path)))") }
        case .smb:
            try await withSMBMount(path: path) { root, relativePath in
                try FileManager.default.removeItem(at: root.appendingPathComponent(relativePath))
            }
        }
    }

    private func sftp(_ command: String) async throws -> String {
        guard let components = URLComponents(string: source.endpoint), let host = components.host else {
            throw RemoteError.invalidConfiguration("SFTP 地址无效")
        }
        if !password.isEmpty {
            throw RemoteError.unsupported("SFTP 密码不能安全传给系统命令，请使用 SSH config 或密钥认证。")
        }
        var arguments = ["-q", "-b", "-", "-oBatchMode=yes", "-oConnectTimeout=10"]
        if let port = components.port { arguments += ["-P", String(port)] }
        arguments.append("\(source.username)@\(host)")
        return try await Self.run("/usr/bin/sftp", arguments: arguments, input: command + "\n")
    }

    private var configuredSMBShare: String? {
        guard let components = URLComponents(string: source.endpoint) else { return nil }
        return components.path.split(separator: "/", omittingEmptySubsequences: true).first.map(String.init)
    }

    private func listSMBShares() async throws -> [RemoteItem] {
        guard let components = URLComponents(string: source.endpoint), let host = components.host else {
            throw RemoteError.invalidConfiguration("SMB 地址无效")
        }
        let user = source.username.isEmpty ? "guest" : source.username
        var arguments = ["view", "-N"]
        if password.isEmpty { arguments.append("-a") }
        arguments.append("//\(user)@\(host)")
        let output = try await Self.run("/usr/bin/smbutil", arguments: arguments)
        return output.split(separator: "\n").compactMap { line in
            guard let range = line.range(of: #"\s{2,}Disk\s{2,}"#, options: .regularExpression) else {
                return nil
            }
            let name = line[..<range.lowerBound].trimmingCharacters(in: .whitespaces)
            guard !name.isEmpty else { return nil }
            return RemoteItem(name: name, path: "/" + name, isDirectory: true, size: 0,
                              modifiedAt: nil, version: name)
        }
    }

    private func smbTarget(path: String) throws -> (share: String, relativePath: String) {
        let pathParts = path.split(separator: "/", omittingEmptySubsequences: true).map(String.init)
        if let share = configuredSMBShare {
            return (share, pathParts.joined(separator: "/"))
        }
        guard let share = pathParts.first else {
            throw RemoteError.invalidConfiguration("请先选择一个 SMB 共享")
        }
        return (share, pathParts.dropFirst().joined(separator: "/"))
    }

    private func withSMBMount<T: Sendable>(
        path: String,
        _ operation: @escaping @Sendable (URL, String) throws -> T
    ) async throws -> T {
        guard let components = URLComponents(string: source.endpoint), let host = components.host else {
            throw RemoteError.invalidConfiguration("SMB 地址无效")
        }
        let target = try smbTarget(path: path)
        let user = source.username.isEmpty ? "guest" : source.username
        let mount = try await Self.mountSMB(
            url: URL(string: "smb://\(host)/\(target.share)")!,
            username: user,
            password: password
        )
        do {
            let value = try operation(mount, target.relativePath)
            _ = try await Self.run("/usr/sbin/diskutil", arguments: ["unmount", "force", mount.path])
            return value
        } catch {
            _ = try? await Self.run("/usr/sbin/diskutil", arguments: ["unmount", "force", mount.path])
            throw error
        }
    }

    private func remotePath(_ path: String) -> String {
        (source.rootPath.normalizedRemotePath as NSString).appendingPathComponent(relative(path))
    }

    private func relative(_ path: String) -> String {
        path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }

    private func localItem(_ url: URL, parent: String) throws -> RemoteItem {
        let values = try url.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey])
        let path = (parent as NSString).appendingPathComponent(url.lastPathComponent).normalizedRemotePath
        let modified = values.contentModificationDate
        return RemoteItem(name: url.lastPathComponent, path: path, isDirectory: values.isDirectory ?? false,
                          size: Int64(values.fileSize ?? 0), modifiedAt: modified,
                          version: "\(modified?.timeIntervalSince1970 ?? 0)-\(values.fileSize ?? 0)")
    }

    private func parseSFTPList(_ output: String, parent: String) -> [RemoteItem] {
        output.split(separator: "\n").compactMap { line in
            let fields = line.split(separator: " ", omittingEmptySubsequences: true)
            guard fields.count >= 9, let size = Int64(fields[4]) else { return nil }
            let listedPath = fields[8...].joined(separator: " ")
            let name = (listedPath as NSString).lastPathComponent
            guard name != ".", name != ".." else { return nil }
            let path = (parent as NSString).appendingPathComponent(name).normalizedRemotePath
            return RemoteItem(name: name, path: path, isDirectory: fields[0].first == "d", size: size,
                              modifiedAt: nil, version: "\(fields[5])-\(fields[6])-\(fields[7])-\(size)")
        }
    }

    private func quoted(_ value: String) -> String {
        "\"" + value.replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"") + "\""
    }

    private static func run(_ executable: String, arguments: [String], input: String? = nil) async throws -> String {
        try await Task.detached {
            let process = Process()
            let output = Pipe()
            let error = Pipe()
            process.executableURL = URL(fileURLWithPath: executable)
            process.arguments = arguments
            process.standardOutput = output
            process.standardError = error
            if let input {
                let pipe = Pipe()
                process.standardInput = pipe
                try process.run()
                pipe.fileHandleForWriting.write(Data(input.utf8))
                try pipe.fileHandleForWriting.close()
            } else {
                try process.run()
            }
            process.waitUntilExit()
            let stdout = output.fileHandleForReading.readDataToEndOfFile()
            let stderr = error.fileHandleForReading.readDataToEndOfFile()
            guard process.terminationStatus == 0 else {
                let message = String(data: stderr, encoding: .utf8) ?? "命令执行失败"
                if message.localizedCaseInsensitiveContains("authentication") ||
                    message.localizedCaseInsensitiveContains("permission denied") {
                    throw RemoteError.server(401, message)
                }
                throw RemoteError.server(Int(process.terminationStatus), message)
            }
            return String(data: stdout, encoding: .utf8) ?? ""
        }.value
    }

    private static func mountSMB(
        url: URL,
        username: String,
        password: String
    ) async throws -> URL {
        let result = await Task.detached {
            let openOptions = NSMutableDictionary()
            openOptions[kNAUIOptionKey] = kNAUIOptionNoUI
            openOptions[kNetFSForceNewSessionKey] = true
            if username == "guest", password.isEmpty {
                openOptions[kNetFSUseGuestKey] = true
            }
            let mountOptions = NSMutableDictionary()
            mountOptions[kNetFSMountFlagsKey] = NSNumber(value: MNT_DONTBROWSE)
            var mountPoints: Unmanaged<CFArray>?
            let status = NetFSMountURLSync(
                url as CFURL,
                nil,
                username as CFString,
                password as CFString,
                openOptions,
                mountOptions,
                &mountPoints
            )
            let paths = mountPoints?.takeRetainedValue() as? [String] ?? []
            return (status, paths)
        }.value
        guard result.0 == 0 else {
            if result.0 == EACCES || result.0 == EPERM {
                throw RemoteError.server(401, "SMB 账户或密码认证失败")
            }
            throw RemoteError.server(Int(result.0), "SMB 挂载失败（\(result.0)）")
        }
        guard let path = result.1.first else {
            throw RemoteError.server(0, "SMB 挂载成功但系统未返回挂载目录")
        }
        return URL(fileURLWithPath: path, isDirectory: true)
    }
}

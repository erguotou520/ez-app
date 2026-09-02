import Foundation

@main
enum IntegrationSmoke {
    static func main() async {
        do {
            try await run()
        } catch {
            FileHandle.standardError.write(Data("SMOKE_ERROR \(error)\n".utf8))
            exit(1)
        }
    }

    private static func run() async throws {
        let environment = ProcessInfo.processInfo.environment
        guard let kind = environment["EZ_TEST_KIND"].flatMap(SourceKind.init(rawValue:)),
              let endpoint = environment["EZ_TEST_ENDPOINT"] else {
            throw RemoteError.invalidConfiguration("缺少 EZ_TEST_KIND 或 EZ_TEST_ENDPOINT")
        }
        let source = DataSource(
            name: "Integration Test",
            kind: kind,
            endpoint: endpoint,
            rootPath: environment["EZ_TEST_ROOT"] ?? "/",
            username: environment["EZ_TEST_USER"] ?? "",
            bucket: environment["EZ_TEST_BUCKET"] ?? "",
            region: environment["EZ_TEST_REGION"] ?? "us-east-1",
            credentialAccount: UUID().uuidString
        )
        let secret = environment["EZ_TEST_SECRET"] ?? ""
        let storage: any RemoteStorage
        switch kind {
        case .webDAV: storage = try WebDAVStorage(source: source, password: secret)
        case .s3: storage = try S3Storage(source: source, secret: secret)
        case .sftp: storage = CommandStorage(source: source, password: secret, mode: .sftp)
        case .smb: storage = CommandStorage(source: source, password: secret, mode: .smb)
        }

        if environment["EZ_TEST_BROWSE_ONLY"] == "1" {
            let items = try await storage.list(path: "/")
            guard !items.isEmpty, items.allSatisfy(\.isDirectory) else {
                throw RemoteError.server(0, "SMB 服务器未返回可浏览的共享目录")
            }
            print("SMOKE_OK kind=\(kind.rawValue) shares=\(items.map(\.name).joined(separator: ","))")
            return
        }
        if environment["EZ_TEST_NESTED_BROWSE"] == "1" {
            let first = try await storage.list(path: "/")
            guard let levelOne = first.first(where: { $0.name == "level-one" }) else {
                throw RemoteError.server(0, "根目录中找不到 level-one")
            }
            let second = try await storage.list(path: levelOne.path)
            guard let levelTwo = second.first(where: { $0.name == "level-two" }) else {
                throw RemoteError.server(0, "一级目录中找不到 level-two")
            }
            let third = try await storage.list(path: levelTwo.path)
            guard third.contains(where: { $0.name == "file.txt" }) else {
                throw RemoteError.server(0, "二级目录中找不到 file.txt")
            }
            print("SMOKE_OK kind=\(kind.rawValue) nested=/level-one/level-two/file.txt")
            return
        }

        let marker = "ez-remote-smoke-\(UUID().uuidString.lowercased())"
        let folder = "/\(marker)"
        let original = "\(folder)/original.txt"
        let moved = "\(folder)/moved.txt"
        let local = FileManager.default.temporaryDirectory.appendingPathComponent(marker)
        let downloaded = local.appendingPathExtension("download")
        let payload = Data("Ez Remote Drive integration smoke test".utf8)
        try payload.write(to: local)
        defer {
            try? FileManager.default.removeItem(at: local)
            try? FileManager.default.removeItem(at: downloaded)
        }

        if kind != .s3 { try await storage.createDirectory(path: folder) }
        try await storage.upload(localURL: local, to: original)
        let listed = try await storage.list(path: folder)
        guard listed.contains(where: { $0.name == "original.txt" }) else {
            throw RemoteError.server(0, "上传后列表中找不到文件")
        }
        _ = try await storage.stat(path: original)
        try await storage.download(path: original, to: downloaded)
        guard try Data(contentsOf: downloaded) == payload else {
            throw RemoteError.server(0, "下载内容与上传内容不一致")
        }
        try await storage.move(from: original, to: moved)
        try await storage.delete(path: moved)
        if kind != .s3 { try await storage.delete(path: folder) }
        print("SMOKE_OK kind=\(kind.rawValue)")
    }
}

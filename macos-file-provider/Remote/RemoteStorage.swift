import Foundation

protocol RemoteStorage: Sendable {
    func list(path: String) async throws -> [RemoteItem]
    func stat(path: String) async throws -> RemoteItem
    func download(path: String, to localURL: URL) async throws
    func upload(localURL: URL, to path: String) async throws
    func createDirectory(path: String) async throws
    func move(from: String, to: String) async throws
    func delete(path: String) async throws
}

enum RemoteError: LocalizedError {
    case invalidConfiguration(String)
    case unsupported(String)
    case server(Int, String)

    var errorDescription: String? {
        switch self {
        case .invalidConfiguration(let value), .unsupported(let value): value
        case .server(let status, let value): "远端返回 \(status)：\(value)"
        }
    }
}

enum RemoteStorageFactory {
    static func make(source: DataSource) throws -> any RemoteStorage {
        let secret = KeychainStore.read(account: source.credentialAccount) ?? ""
        switch source.kind {
        case .webDAV: return try WebDAVStorage(source: source, password: secret)
        case .s3: return try S3Storage(source: source, secret: secret)
        case .sftp: return CommandStorage(source: source, password: secret, mode: .sftp)
        case .smb: return CommandStorage(source: source, password: secret, mode: .smb)
        }
    }
}

extension String {
    var normalizedRemotePath: String {
        hasPrefix("/") ? self : "/" + self
    }
}


import Foundation

enum SourceKind: String, Codable, CaseIterable, Identifiable {
    case webDAV, s3, sftp, smb
    var id: String { rawValue }
    var title: String {
        switch self {
        case .webDAV: "WebDAV"
        case .s3: "S3"
        case .sftp: "SFTP"
        case .smb: "SMB"
        }
    }
}

struct DataSource: Codable, Identifiable, Hashable {
    var id = UUID()
    var name: String
    var kind: SourceKind
    var endpoint: String
    var rootPath: String = "/"
    var username: String = ""
    var bucket: String = ""
    var region: String = "us-east-1"
    var credentialAccount: String
    var isMounted = true

    var safeDisplayName: String {
        DisplayNameRules.sanitized(name)
    }
}

enum DisplayNameRules {
    static func validationMessage(_ value: String) -> String? {
        let name = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if name.isEmpty { return "显示名称不能为空" }
        if name == "." || name == ".." { return "显示名称不能是 . 或 .." }
        if name.unicodeScalars.contains(where: {
            $0 == "/" || $0 == ":" || CharacterSet.controlCharacters.contains($0)
        }) {
            return "显示名称不能包含 /、: 或换行等控制字符"
        }
        return nil
    }

    static func sanitized(_ value: String) -> String {
        var name = value.trimmingCharacters(in: .whitespacesAndNewlines)
        name.unicodeScalars.removeAll {
            $0 == "/" || $0 == ":" || CharacterSet.controlCharacters.contains($0)
        }
        if name.isEmpty || name == "." || name == ".." {
            return "未命名存储源"
        }
        return name
    }

    static func comparisonKey(_ value: String) -> String {
        value
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive],
                     locale: .current)
    }
}

struct RemoteItem: Codable, Identifiable, Hashable {
    var id: String { path }
    let name: String
    let path: String
    let isDirectory: Bool
    let size: Int64
    let modifiedAt: Date?
    let version: String
}

enum PendingChangeKind: String, Codable {
    case create, modify, move, delete
}

struct PendingChange: Codable, Identifiable, Hashable {
    var id = UUID()
    let sourceID: UUID
    let kind: PendingChangeKind
    let path: String
    var destinationPath: String?
    var stagedFile: String?
    var createdAt = Date()
    var isSelected = true
}

enum SharedConstants {
    static let appGroup = "group.com.erguotou.ezremote"
    static let domainPrefix = "com.erguotou.ezremote.source."
    static let appDomainIdentifier = "com.erguotou.ezfiles.domain"
}

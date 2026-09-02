import Foundation
import LocalAuthentication
import Security

actor SharedStore {
    static let shared = SharedStore()

    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private let baseURL: URL

    init() {
        let localGroupURL = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/Group Containers", isDirectory: true)
            .appendingPathComponent(SharedConstants.appGroup, isDirectory: true)
        baseURL = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: SharedConstants.appGroup
        ) ?? localGroupURL
        try? FileManager.default.createDirectory(at: baseURL, withIntermediateDirectories: true)
        try? FileManager.default.createDirectory(
            at: baseURL.appendingPathComponent("Staged", isDirectory: true),
            withIntermediateDirectories: true
        )
    }

    var stagedURL: URL { baseURL.appendingPathComponent("Staged", isDirectory: true) }

    func loadSources() -> [DataSource] { load("sources.json", fallback: []) }
    func saveSources(_ value: [DataSource]) throws { try save(value, name: "sources.json") }
    func loadChanges() -> [PendingChange] { load("changes.json", fallback: []) }
    func saveChanges(_ value: [PendingChange]) throws { try save(value, name: "changes.json") }

    func appendChange(_ change: PendingChange) throws {
        var changes = loadChanges()
        changes.removeAll { $0.sourceID == change.sourceID && $0.path == change.path }
        changes.append(change)
        try saveChanges(changes)
    }

    func source(id: UUID) -> DataSource? { loadSources().first { $0.id == id } }

    nonisolated static func loadSourcesSynchronously() -> [DataSource] {
        let localGroupURL = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/Group Containers", isDirectory: true)
            .appendingPathComponent(SharedConstants.appGroup, isDirectory: true)
        let candidates = [
            FileManager.default.containerURL(
                forSecurityApplicationGroupIdentifier: SharedConstants.appGroup
            ),
            localGroupURL
        ].compactMap { $0 }
        for baseURL in candidates {
            let url = baseURL.appendingPathComponent("sources.json")
            guard let data = try? Data(contentsOf: url),
                  let sources = try? JSONDecoder().decode([DataSource].self, from: data) else {
                continue
            }
            return sources
        }
        return []
    }

    private func load<T: Decodable>(_ name: String, fallback: T) -> T {
        guard let data = try? Data(contentsOf: baseURL.appendingPathComponent(name)),
              let value = try? decoder.decode(T.self, from: data) else { return fallback }
        return value
    }

    private func save<T: Encodable>(_ value: T, name: String) throws {
        let data = try encoder.encode(value)
        try data.write(to: baseURL.appendingPathComponent(name), options: .atomic)
    }
}

enum KeychainStore {
    private static let legacyService = "com.erguotou.ezremote"
    private static let vaultService = "com.erguotou.ezfiles.credentials"
    private static let vaultAccount = "vault-v1"
    private static let cacheLock = NSLock()
    private static var cachedVault: [String: String]?

    static func save(_ secret: String, account: String) throws {
        var vault = loadVault(allowInteraction: true) ?? [:]
        vault[account] = secret
        try saveVault(vault)
    }

    static func read(account: String) -> String? {
        if let vault = loadVault(allowInteraction: false) {
            return vault[account]
        }
        return readLegacy(account: account, allowInteraction: false)
    }

    static func migrateLegacy(accounts: [String]) throws {
        guard loadVault(allowInteraction: false) == nil else { return }
        var vault: [String: String] = [:]
        for account in accounts {
            guard let secret = readLegacy(account: account, allowInteraction: true) else { continue }
            vault[account] = secret
        }
        try saveVault(vault)
    }

    private static func saveVault(_ vault: [String: String]) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: vaultService,
            kSecAttrAccount as String: vaultAccount
        ]
        SecItemDelete(query as CFDictionary)
        var insert = query
        insert[kSecValueData as String] = try JSONEncoder().encode(vault)
        if let access = sharedAccess() {
            insert[kSecAttrAccess as String] = access
        }
        let status = SecItemAdd(insert as CFDictionary, nil)
        guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
        setCachedVault(vault)
    }

    private static func loadVault(allowInteraction: Bool) -> [String: String]? {
        if let cached = getCachedVault() { return cached }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: vaultService,
            kSecAttrAccount as String: vaultAccount,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var request = query
        if !allowInteraction {
            let context = LAContext()
            context.interactionNotAllowed = true
            request[kSecUseAuthenticationContext as String] = context
        }
        var value: CFTypeRef?
        guard SecItemCopyMatching(request as CFDictionary, &value) == errSecSuccess,
              let data = value as? Data,
              let vault = try? JSONDecoder().decode([String: String].self, from: data) else {
            return nil
        }
        setCachedVault(vault)
        return vault
    }

    private static func readLegacy(account: String, allowInteraction: Bool) -> String? {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: legacyService,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        if !allowInteraction {
            let context = LAContext()
            context.interactionNotAllowed = true
            query[kSecUseAuthenticationContext as String] = context
        }
        var value: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &value) == errSecSuccess,
              let data = value as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private static func getCachedVault() -> [String: String]? {
        cacheLock.lock()
        defer { cacheLock.unlock() }
        return cachedVault
    }

    private static func setCachedVault(_ vault: [String: String]) {
        cacheLock.lock()
        cachedVault = vault
        cacheLock.unlock()
    }

    static func removeLegacySMBCredential(source: DataSource) {
        guard let components = URLComponents(string: source.endpoint),
              let host = components.host else { return }
        let query: [String: Any] = [
            kSecClass as String: kSecClassInternetPassword,
            kSecAttrServer as String: host,
            kSecAttrAccount as String: source.username,
            kSecAttrProtocol as String: kSecAttrProtocolSMB
        ]
        SecItemDelete(query as CFDictionary)
    }

    private static func sharedAccess() -> SecAccess? {
        let appURL = Bundle.main.bundleURL
        let hostURL = appURL.appendingPathComponent("Contents/MacOS/EzFiles")
        let extensionURL = appURL.appendingPathComponent(
            "Contents/PlugIns/EzRemoteFileProvider.appex/Contents/MacOS/EzRemoteFileProvider"
        )
        var trusted: [SecTrustedApplication] = []
        for url in [hostURL, extensionURL] where FileManager.default.fileExists(atPath: url.path) {
            var application: SecTrustedApplication?
            let status = url.path.withCString {
                SecTrustedApplicationCreateFromPath($0, &application)
            }
            if status == errSecSuccess, let application { trusted.append(application) }
        }
        guard !trusted.isEmpty else { return nil }
        var access: SecAccess?
        let status = SecAccessCreate("EzFiles credentials" as CFString, trusted as CFArray, &access)
        return status == errSecSuccess ? access : nil
    }

}

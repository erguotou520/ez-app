import AppKit
import FileProvider
import Foundation
import SwiftUI

@MainActor
final class AppModel: ObservableObject {
    @Published var sources: [DataSource] = []
    @Published var changes: [PendingChange] = []
    @Published var selectedSourceID: UUID?
    @Published var items: [RemoteItem] = []
    @Published var currentPath = "/"
    @Published var message = ""
    @Published var syncRemoteDeletes = false

    func load() async {
        sources = await SharedStore.shared.loadSources()
        do {
            try KeychainStore.migrateLegacy(accounts: sources.map(\.credentialAccount))
        } catch {
            message = "合并凭据失败：\(error.localizedDescription)"
        }
        await migrateDomainsIfNeeded()
        changes = await SharedStore.shared.loadChanges()
        selectedSourceID = selectedSourceID ?? sources.first?.id
        await refresh()
    }

    func add(_ source: DataSource, secret: String) async -> Bool {
        let key = DisplayNameRules.comparisonKey(source.name)
        guard !sources.contains(where: { DisplayNameRules.comparisonKey($0.name) == key }) else {
            message = "显示名称已存在，请换一个名称。"
            return false
        }
        do {
            try KeychainStore.save(secret, account: source.credentialAccount)
            if source.kind == .smb {
                KeychainStore.removeLegacySMBCredential(source: source)
            }
            sources.append(source)
            try await SharedStore.shared.saveSources(sources)
            try await updateDomain()
            selectedSourceID = source.id
            await refresh()
            return true
        } catch {
            message = error.localizedDescription
            return false
        }
    }

    func update(_ source: DataSource, secret: String) async -> Bool {
        let key = DisplayNameRules.comparisonKey(source.name)
        guard !sources.contains(where: {
            $0.id != source.id && DisplayNameRules.comparisonKey($0.name) == key
        }) else {
            message = "显示名称已存在，请换一个名称。"
            return false
        }
        guard let index = sources.firstIndex(where: { $0.id == source.id }) else {
            message = "存储源不存在。"
            return false
        }
        do {
            if !secret.isEmpty {
                try KeychainStore.save(secret, account: source.credentialAccount)
                if source.kind == .smb {
                    KeychainStore.removeLegacySMBCredential(source: source)
                }
            }
            sources[index] = source
            try await SharedStore.shared.saveSources(sources)
            try await updateDomain()
            selectedSourceID = source.id
            await refresh(path: "/")
            return true
        } catch {
            message = error.localizedDescription
            return false
        }
    }

    func remove(_ source: DataSource) async {
        do {
            sources.removeAll { $0.id == source.id }
            try await SharedStore.shared.saveSources(sources)
            try await updateDomain()
            selectedSourceID = sources.first?.id
            await refresh()
        } catch { message = error.localizedDescription }
    }

    func refresh(path: String? = nil) async {
        if let path { currentPath = path }
        guard let source = sources.first(where: { $0.id == selectedSourceID }) else {
            items = []
            return
        }
        do {
            items = try await RemoteStorageFactory.make(source: source)
                .list(path: currentPath)
                .sorted {
                    if $0.isDirectory != $1.isDirectory {
                        return $0.isDirectory
                    }
                    let order = $0.name.localizedStandardCompare($1.name)
                    if order != .orderedSame {
                        return order == .orderedAscending
                    }
                    return $0.path < $1.path
                }
            changes = await SharedStore.shared.loadChanges()
            message = ""
        } catch { items = []; message = error.localizedDescription }
    }

    func open(_ item: RemoteItem) async {
        if item.isDirectory {
            await refresh(path: item.path)
            return
        }
        guard let source = sources.first(where: { $0.id == selectedSourceID }) else { return }
        do {
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(item.name)
            try await RemoteStorageFactory.make(source: source).download(path: item.path, to: url)
            NSWorkspace.shared.open(url)
        } catch { message = error.localizedDescription }
    }

    func syncSelected() async {
        do {
            var completed = Set<UUID>()
            for change in changes where change.isSelected {
                guard let source = sources.first(where: { $0.id == change.sourceID }) else { continue }
                let remote = try RemoteStorageFactory.make(source: source)
                switch change.kind {
                case .create, .modify:
                    guard let file = change.stagedFile else { continue }
                    try await remote.upload(localURL: URL(fileURLWithPath: file), to: change.path)
                case .move:
                    guard let destination = change.destinationPath else { continue }
                    try await remote.move(from: change.path, to: destination)
                case .delete:
                    guard syncRemoteDeletes else { continue }
                    try await remote.delete(path: change.path)
                }
                completed.insert(change.id)
            }
            changes.removeAll { completed.contains($0.id) }
            try await SharedStore.shared.saveChanges(changes)
            await refresh()
        } catch { message = error.localizedDescription }
    }

    func clearDownloaded() async {
        guard let id = selectedSourceID else { return }
        let domain = appDomain()
        guard let manager = NSFileProviderManager(for: domain) else { return }
        do {
            try await manager.evictItem(identifier: encodeIdentifier(sourceID: id, path: "/"))
            message = "已请求系统清理本地下载，远端文件不受影响。"
        } catch { message = error.localizedDescription }
    }

    private func appDomain() -> NSFileProviderDomain {
        NSFileProviderDomain(
            identifier: .init(SharedConstants.appDomainIdentifier),
            displayName: "EzFiles"
        )
    }

    private func updateDomain() async throws {
        let domain = appDomain()
        try? await NSFileProviderManager.remove(domain)
        let mountedSources = sources.filter(\.isMounted)
        guard !mountedSources.isEmpty else { return }
        if #available(macOS 15.0, *) {
            domain.userInfo = ["sources": try JSONEncoder().encode(mountedSources)]
        }
        try await NSFileProviderManager.add(domain)
    }

    private func migrateDomainsIfNeeded() async {
        let defaults = UserDefaults.standard
        let currentVersion = 8
        guard defaults.integer(forKey: "fileProviderDomainVersion") < currentVersion else { return }
        let legacyAppDomain = NSFileProviderDomain(
            identifier: .init("com.erguotou.ezremote.domain"),
            displayName: "EzFiles"
        )
        try? await NSFileProviderManager.remove(legacyAppDomain)
        for source in sources {
            let legacyDomain = NSFileProviderDomain(
                identifier: .init(SharedConstants.domainPrefix + source.id.uuidString),
                displayName: source.safeDisplayName
            )
            try? await NSFileProviderManager.remove(legacyDomain)
        }
        try? await updateDomain()
        defaults.set(currentVersion, forKey: "fileProviderDomainVersion")
    }
}

import FileProvider
import Foundation

final class FileProviderExtension: NSObject, NSFileProviderReplicatedExtension {
    let sources: [DataSource]

    required init(domain: NSFileProviderDomain) {
        var domainSources: [DataSource]?
        if #available(macOS 15.0, *),
           let data = domain.userInfo?["sources"] as? Data {
            domainSources = try? JSONDecoder().decode([DataSource].self, from: data)
        }
        sources = domainSources ?? SharedStore.loadSourcesSynchronously()
        super.init()
        NSLog("[EzRemote] extension initialized with %ld sources", sources.count)
    }

    func invalidate() {}

    func item(for identifier: NSFileProviderItemIdentifier, request: NSFileProviderRequest,
              completionHandler: @escaping (NSFileProviderItem?, Error?) -> Void) -> Progress {
        let progress = Progress(totalUnitCount: 1)
        NSLog("[EzRemote] item request %@", identifier.rawValue)
        if identifier == .rootContainer {
            let id = sources.first?.id ?? UUID(uuidString: "00000000-0000-0000-0000-000000000000")!
            let version = sources.map(\.id.uuidString).joined(separator: ",")
            let root = RemoteItem(name: "EzFiles", path: "/", isDirectory: true, size: 0,
                                  modifiedAt: nil, version: version)
            completionHandler(ProviderItem(sourceID: id, remote: root), nil)
            progress.completedUnitCount = 1
            return progress
        }
        if let ref = decodeIdentifier(identifier), ref.path == "/",
           let source = source(id: ref.sourceID) {
            let root = RemoteItem(name: source.safeDisplayName, path: "/", isDirectory: true, size: 0,
                                  modifiedAt: nil, version: source.id.uuidString)
            completionHandler(ProviderItem(sourceID: source.id, remote: root, isSourceRoot: true), nil)
            progress.completedUnitCount = 1
            return progress
        }
        Task {
            do {
                guard let ref = decodeIdentifier(identifier) else { throw NSFileProviderError(.noSuchItem) }
                guard let source = source(id: ref.sourceID) else { throw NSFileProviderError(.noSuchItem) }
                let storage = try RemoteStorageFactory.make(source: source)
                completionHandler(ProviderItem(sourceID: source.id, remote: try await storage.stat(path: ref.path)), nil)
            } catch { completionHandler(nil, error) }
            progress.completedUnitCount = 1
        }
        return progress
    }

    func fetchContents(for itemIdentifier: NSFileProviderItemIdentifier, version: NSFileProviderItemVersion?,
                       request: NSFileProviderRequest,
                       completionHandler: @escaping (URL?, NSFileProviderItem?, Error?) -> Void) -> Progress {
        let progress = Progress(totalUnitCount: 1)
        NSLog("[EzRemote] fetch request")
        Task {
            do {
                guard let ref = decodeIdentifier(itemIdentifier) else { throw NSFileProviderError(.noSuchItem) }
                guard ref.path != "/" else { throw NSFileProviderError(.noSuchItem) }
                guard let source = source(id: ref.sourceID) else { throw NSFileProviderError(.noSuchItem) }
                let storage = try RemoteStorageFactory.make(source: source)
                let item = try await storage.stat(path: ref.path)
                let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "-" + item.name)
                try await storage.download(path: ref.path, to: url)
                completionHandler(url, ProviderItem(sourceID: source.id, remote: item), nil)
            } catch { completionHandler(nil, nil, error) }
            progress.completedUnitCount = 1
        }
        return progress
    }

    func createItem(basedOn itemTemplate: NSFileProviderItem, fields: NSFileProviderItemFields,
                    contents url: URL?, options: NSFileProviderCreateItemOptions = [],
                    request: NSFileProviderRequest,
                    completionHandler: @escaping (NSFileProviderItem?, NSFileProviderItemFields, Bool, Error?) -> Void)
    -> Progress {
        let progress = Progress(totalUnitCount: 1)
        Task {
            do {
                let (source, path) = try destination(parent: itemTemplate.parentItemIdentifier,
                                                     name: itemTemplate.filename)
                let staged = try stage(contents: url, name: itemTemplate.filename)
                try await SharedStore.shared.appendChange(PendingChange(
                    sourceID: source.id, kind: .create, path: path, stagedFile: staged?.path
                ))
                let remote = RemoteItem(name: itemTemplate.filename, path: path,
                                        isDirectory: itemTemplate.contentType == .folder,
                                        size: (itemTemplate.documentSize ?? nil)?.int64Value ?? 0,
                                        modifiedAt: Date(), version: UUID().uuidString)
                completionHandler(ProviderItem(sourceID: source.id, remote: remote), [], false, nil)
            } catch { completionHandler(nil, [], false, error) }
            progress.completedUnitCount = 1
        }
        return progress
    }

    func modifyItem(_ item: NSFileProviderItem, baseVersion: NSFileProviderItemVersion,
                    changedFields: NSFileProviderItemFields, contents url: URL?,
                    options: NSFileProviderModifyItemOptions = [], request: NSFileProviderRequest,
                    completionHandler: @escaping (NSFileProviderItem?, NSFileProviderItemFields, Bool, Error?) -> Void)
    -> Progress {
        let progress = Progress(totalUnitCount: 1)
        Task {
            do {
                guard let ref = decodeIdentifier(item.itemIdentifier) else { throw NSFileProviderError(.noSuchItem) }
                guard ref.path != "/" else { throw NSFileProviderError(.noSuchItem) }
                guard let source = source(id: ref.sourceID) else { throw NSFileProviderError(.noSuchItem) }
                var destination: String?
                if changedFields.contains(.filename) || changedFields.contains(.parentItemIdentifier) {
                    let target = try self.destination(parent: item.parentItemIdentifier, name: item.filename)
                    guard target.0.id == source.id else { throw NSFileProviderError(.noSuchItem) }
                    destination = target.1
                }
                let staged = try stage(contents: url, name: item.filename)
                try await SharedStore.shared.appendChange(PendingChange(
                    sourceID: source.id, kind: destination == nil ? .modify : .move,
                    path: ref.path, destinationPath: destination, stagedFile: staged?.path
                ))
                let remote = RemoteItem(name: item.filename, path: destination ?? ref.path,
                                        isDirectory: item.contentType == .folder,
                                        size: (item.documentSize ?? nil)?.int64Value ?? 0,
                                        modifiedAt: Date(), version: UUID().uuidString)
                completionHandler(ProviderItem(sourceID: source.id, remote: remote), [], false, nil)
            } catch { completionHandler(nil, [], false, error) }
            progress.completedUnitCount = 1
        }
        return progress
    }

    func deleteItem(identifier: NSFileProviderItemIdentifier, baseVersion: NSFileProviderItemVersion,
                    options: NSFileProviderDeleteItemOptions = [], request: NSFileProviderRequest,
                    completionHandler: @escaping (Error?) -> Void) -> Progress {
        let progress = Progress(totalUnitCount: 1)
        Task {
            do {
                guard let ref = decodeIdentifier(identifier) else { throw NSFileProviderError(.noSuchItem) }
                guard ref.path != "/" else { throw NSFileProviderError(.noSuchItem) }
                guard source(id: ref.sourceID) != nil else { throw NSFileProviderError(.noSuchItem) }
                try await SharedStore.shared.appendChange(PendingChange(sourceID: ref.sourceID, kind: .delete,
                                                                         path: ref.path))
                completionHandler(nil)
            } catch { completionHandler(error) }
            progress.completedUnitCount = 1
        }
        return progress
    }

    func enumerator(for containerItemIdentifier: NSFileProviderItemIdentifier,
                    request: NSFileProviderRequest) throws -> NSFileProviderEnumerator {
        NSLog("[EzRemote] enumerator request %@", containerItemIdentifier.rawValue)
        return ProviderEnumerator(sources: sources, container: containerItemIdentifier)
    }

    private func source(id: UUID) -> DataSource? {
        sources.first { $0.id == id && $0.isMounted }
    }

    private func destination(parent: NSFileProviderItemIdentifier, name: String) throws
    -> (DataSource, String) {
        guard parent != .rootContainer,
              let ref = decodeIdentifier(parent),
              let source = source(id: ref.sourceID) else {
            throw NSFileProviderError(.noSuchItem)
        }
        return (source, (ref.path as NSString).appendingPathComponent(name))
    }

    private func stage(contents: URL?, name: String) throws -> URL? {
        guard let contents else { return nil }
        let root = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: SharedConstants.appGroup)?
            .appendingPathComponent("Staged", isDirectory: true) ?? FileManager.default.temporaryDirectory
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let target = root.appendingPathComponent(UUID().uuidString + "-" + name)
        try FileManager.default.copyItem(at: contents, to: target)
        return target
    }
}

import FileProvider

final class ProviderEnumerator: NSObject, NSFileProviderEnumerator {
    let sources: [DataSource]
    let container: NSFileProviderItemIdentifier

    init(sources: [DataSource], container: NSFileProviderItemIdentifier) {
        self.sources = sources
        self.container = container
    }

    func invalidate() {}

    func enumerateItems(for observer: NSFileProviderEnumerationObserver, startingAt page: NSFileProviderPage) {
        NSLog("[EzRemote] enumerateItems %@", container.rawValue)
        if container == .workingSet || container == .trashContainer {
            observer.finishEnumerating(upTo: nil)
            return
        }
        if container == .rootContainer {
            observer.didEnumerate(sources.filter(\.isMounted).map(sourceRootItem))
            observer.finishEnumerating(upTo: nil)
            return
        }
        guard let ref = decodeIdentifier(container),
              let source = sources.first(where: { $0.id == ref.sourceID && $0.isMounted }) else {
            observer.finishEnumeratingWithError(NSFileProviderError(.noSuchItem))
            return
        }
        Task {
            do {
                NSLog("[EzRemote] enumerateItems remote start")
                let remote = try RemoteStorageFactory.make(source: source)
                observer.didEnumerate(try await remote.list(path: ref.path).map {
                    ProviderItem(sourceID: source.id, remote: $0)
                })
                NSLog("[EzRemote] enumerateItems remote finished")
                observer.finishEnumerating(upTo: nil)
            } catch {
                NSLog("[EzFiles] enumerateItems failed for %@: %@", ref.path,
                      String(describing: error))
                observer.finishEnumeratingWithError(Self.wrap(error))
            }
        }
    }

    func enumerateChanges(for observer: NSFileProviderChangeObserver, from syncAnchor: NSFileProviderSyncAnchor) {
        NSLog("[EzRemote] enumerateChanges %@", container.rawValue)
        if container == .workingSet || container == .trashContainer {
            observer.finishEnumeratingChanges(upTo: Self.anchor(), moreComing: false)
            return
        }
        if container == .rootContainer {
            observer.didUpdate(sources.filter(\.isMounted).map(sourceRootItem))
            observer.finishEnumeratingChanges(upTo: Self.anchor(), moreComing: false)
            return
        }
        guard let ref = decodeIdentifier(container),
              let source = sources.first(where: { $0.id == ref.sourceID && $0.isMounted }) else {
            observer.finishEnumeratingWithError(NSFileProviderError(.noSuchItem))
            return
        }
        Task {
            do {
                NSLog("[EzRemote] enumerateChanges remote start")
                let remote = try RemoteStorageFactory.make(source: source)
                observer.didUpdate(try await remote.list(path: ref.path).map {
                    ProviderItem(sourceID: source.id, remote: $0)
                })
                observer.finishEnumeratingChanges(upTo: Self.anchor(), moreComing: false)
                NSLog("[EzRemote] enumerateChanges remote finished")
            } catch {
                NSLog("[EzFiles] enumerateChanges failed for %@: %@", ref.path,
                      String(describing: error))
                observer.finishEnumeratingWithError(Self.wrap(error))
            }
        }
    }

    func currentSyncAnchor(completionHandler: @escaping (NSFileProviderSyncAnchor?) -> Void) {
        completionHandler(Self.anchor())
    }

    private static func anchor() -> NSFileProviderSyncAnchor {
        NSFileProviderSyncAnchor(Data(String(Date().timeIntervalSince1970).utf8))
    }

    private func sourceRootItem(_ source: DataSource) -> ProviderItem {
        ProviderItem(
            sourceID: source.id,
            remote: RemoteItem(name: source.safeDisplayName, path: "/", isDirectory: true, size: 0,
                               modifiedAt: nil, version: source.id.uuidString),
            isSourceRoot: true
        )
    }

    private static func wrap(_ error: Error) -> Error {
        if let remote = error as? RemoteError {
            switch remote {
            case .server(let status, _) where status == 404:
                return NSFileProviderError(.noSuchItem)
            case .server(let status, _) where status == 401 || status == 403:
                return NSFileProviderError(.notAuthenticated)
            default:
                return NSFileProviderError(.cannotSynchronize)
            }
        }
        if let url = error as? URLError, url.code == .cannotConnectToHost {
            return NSFileProviderError(.serverUnreachable)
        }
        return error
    }
}

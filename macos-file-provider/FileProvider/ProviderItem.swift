import FileProvider
import UniformTypeIdentifiers

final class ProviderItem: NSObject, NSFileProviderItem {
    let sourceID: UUID
    let remote: RemoteItem
    let isSourceRoot: Bool

    init(sourceID: UUID, remote: RemoteItem, isSourceRoot: Bool = false) {
        self.sourceID = sourceID
        self.remote = remote
        self.isSourceRoot = isSourceRoot
    }

    var itemIdentifier: NSFileProviderItemIdentifier {
        remote.path == "/" && !isSourceRoot
            ? .rootContainer
            : encodeIdentifier(sourceID: sourceID, path: remote.path)
    }
    var parentItemIdentifier: NSFileProviderItemIdentifier {
        if isSourceRoot { return .rootContainer }
        let parent = (remote.path as NSString).deletingLastPathComponent
        return encodeIdentifier(sourceID: sourceID, path: parent.isEmpty ? "/" : parent)
    }
    var filename: String { remote.name }
    var contentType: UTType { remote.isDirectory ? .folder : (UTType(filenameExtension: (remote.name as NSString).pathExtension) ?? .data) }
    var documentSize: NSNumber? { remote.isDirectory ? nil : NSNumber(value: remote.size) }
    var contentModificationDate: Date? { remote.modifiedAt }
    var creationDate: Date? { remote.modifiedAt }
    var itemVersion: NSFileProviderItemVersion {
        let data = Data(remote.version.utf8)
        return NSFileProviderItemVersion(contentVersion: data, metadataVersion: data)
    }
    var capabilities: NSFileProviderItemCapabilities {
        if isSourceRoot {
            return [.allowsReading, .allowsWriting, .allowsAddingSubItems, .allowsContentEnumerating]
        }
        if remote.isDirectory {
            return [.allowsReading, .allowsWriting, .allowsAddingSubItems,
                    .allowsContentEnumerating, .allowsDeleting, .allowsRenaming]
        }
        return [.allowsReading, .allowsWriting, .allowsRenaming,
                .allowsReparenting, .allowsDeleting]
    }
}

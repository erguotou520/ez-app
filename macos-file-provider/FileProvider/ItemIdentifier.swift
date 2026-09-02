import FileProvider
import Foundation

struct ItemReference: Codable {
    let sourceID: UUID
    let path: String
}

func encodeIdentifier(sourceID: UUID, path: String) -> NSFileProviderItemIdentifier {
    let data = try! JSONEncoder().encode(ItemReference(sourceID: sourceID, path: path))
    return NSFileProviderItemIdentifier(data.base64EncodedString())
}

func decodeIdentifier(_ identifier: NSFileProviderItemIdentifier) -> ItemReference? {
    guard let data = Data(base64Encoded: identifier.rawValue) else { return nil }
    return try? JSONDecoder().decode(ItemReference.self, from: data)
}


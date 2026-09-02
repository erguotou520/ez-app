import Foundation

final class WebDAVStorage: NSObject, RemoteStorage, XMLParserDelegate, @unchecked Sendable {
    private let baseURL: URL
    private let authorization: String?
    private var parsed: [RemoteItem] = []
    private var fields: [String: String] = [:]
    private var current = ""

    init(source: DataSource, password: String) throws {
        guard let url = URL(string: source.endpoint) else {
            throw RemoteError.invalidConfiguration("WebDAV 地址无效")
        }
        baseURL = url.appendingPathComponent(source.rootPath)
        authorization = source.username.isEmpty ? nil :
            "Basic " + Data("\(source.username):\(password)".utf8).base64EncodedString()
    }

    func list(path: String) async throws -> [RemoteItem] {
        var request = request(path: path, method: "PROPFIND", isDirectory: true)
        request.setValue("1", forHTTPHeaderField: "Depth")
        request.httpBody = Data("""
        <?xml version="1.0"?><propfind xmlns="DAV:"><prop>
        <displayname/><getcontentlength/><getlastmodified/><getetag/><resourcetype/>
        </prop></propfind>
        """.utf8)
        let data = try await send(request, accepted: 207)
        parsed = []
        let parser = XMLParser(data: data)
        parser.delegate = self
        guard parser.parse() else { throw parser.parserError ?? RemoteError.server(207, "XML 解析失败") }
        return Array(parsed.dropFirst())
    }

    func stat(path: String) async throws -> RemoteItem {
        var request = request(path: path, method: "PROPFIND")
        request.setValue("0", forHTTPHeaderField: "Depth")
        let data = try await send(request, accepted: 207)
        parsed = []
        let parser = XMLParser(data: data)
        parser.delegate = self
        guard parser.parse(), let item = parsed.first else { throw RemoteError.server(207, "缺少文件元数据") }
        return item
    }

    func download(path: String, to localURL: URL) async throws {
        let data = try await send(request(path: path, method: "GET"), accepted: 200)
        try data.write(to: localURL, options: .atomic)
    }

    func upload(localURL: URL, to path: String) async throws {
        var value = request(path: path, method: "PUT")
        value.httpBody = try Data(contentsOf: localURL)
        _ = try await send(value, accepted: 200...204)
    }

    func createDirectory(path: String) async throws {
        _ = try await send(request(path: path, method: "MKCOL"), accepted: 200...201)
    }

    func move(from: String, to: String) async throws {
        var value = request(path: from, method: "MOVE")
        value.setValue(url(path: to).absoluteString, forHTTPHeaderField: "Destination")
        _ = try await send(value, accepted: 200...204)
    }

    func delete(path: String) async throws {
        _ = try await send(request(path: path, method: "DELETE"), accepted: 200...204)
    }

    private func url(path: String, isDirectory: Bool = false) -> URL {
        let relative = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !relative.isEmpty else {
            return isDirectory ? baseURL.appendingPathComponent("", isDirectory: true) : baseURL
        }
        return baseURL.appendingPathComponent(relative, isDirectory: isDirectory)
    }

    private func request(path: String, method: String, isDirectory: Bool = false) -> URLRequest {
        var value = URLRequest(url: url(path: path, isDirectory: isDirectory))
        value.httpMethod = method
        if let authorization { value.setValue(authorization, forHTTPHeaderField: "Authorization") }
        return value
    }

    private func send(_ request: URLRequest, accepted: Int) async throws -> Data {
        try await send(request, accepted: accepted...accepted)
    }

    private func send(_ request: URLRequest, accepted: ClosedRange<Int>) async throws -> Data {
        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard accepted.contains(status) else {
            throw RemoteError.server(status, String(data: data, encoding: .utf8) ?? "")
        }
        return data
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        current = elementName.lowercased()
        if current.hasSuffix("response") { fields = [:] }
        if current.hasSuffix("collection") { fields["directory"] = "true" }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) { fields[current, default: ""] += string }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?,
                qualifiedName qName: String?) {
        guard elementName.lowercased().hasSuffix("response") else { return }
        let href = fields.first { $0.key.hasSuffix("href") }?.value ?? "/"
        let decoded = href.removingPercentEncoding ?? href
        let name = fields.first { $0.key.hasSuffix("displayname") }?.value
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let serverPath = URL(string: decoded)?.path ?? decoded
        let path = remotePath(from: serverPath)
        parsed.append(RemoteItem(
            name: name?.isEmpty == false ? name! : URL(fileURLWithPath: path).lastPathComponent,
            path: path, isDirectory: fields["directory"] == "true",
            size: Int64(fields.first { $0.key.hasSuffix("getcontentlength") }?.value ?? "") ?? 0,
            modifiedAt: nil,
            version: fields.first { $0.key.hasSuffix("getetag") }?.value ?? path
        ))
    }

    private func remotePath(from serverPath: String) -> String {
        let root = baseURL.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let value = serverPath.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if value == root { return "/" }
        if !root.isEmpty, value.hasPrefix(root + "/") {
            return "/" + value.dropFirst(root.count + 1)
        }
        return serverPath.normalizedRemotePath
    }
}

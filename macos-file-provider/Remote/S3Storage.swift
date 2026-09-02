import CryptoKit
import Foundation

final class S3Storage: RemoteStorage, @unchecked Sendable {
    private let source: DataSource
    private let secret: String

    init(source: DataSource, secret: String) throws {
        guard URL(string: source.endpoint) != nil, !source.bucket.isEmpty else {
            throw RemoteError.invalidConfiguration("S3 需要 endpoint 和 bucket")
        }
        self.source = source
        self.secret = secret
    }

    func list(path: String) async throws -> [RemoteItem] {
        let prefix = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let data = try await request(method: "GET", path: "/", query: [
            URLQueryItem(name: "list-type", value: "2"),
            URLQueryItem(name: "delimiter", value: "/"),
            URLQueryItem(name: "prefix", value: prefix.isEmpty ? nil : prefix + "/")
        ])
        return S3ListParser.parse(data: data, prefix: prefix)
    }

    func stat(path: String) async throws -> RemoteItem {
        let (_, response) = try await requestWithResponse(method: "HEAD", path: path)
        let name = URL(fileURLWithPath: path).lastPathComponent
        return RemoteItem(name: name, path: path, isDirectory: path.hasSuffix("/"),
                          size: Int64(response.value(forHTTPHeaderField: "Content-Length") ?? "") ?? 0,
                          modifiedAt: nil, version: response.value(forHTTPHeaderField: "ETag") ?? path)
    }

    func download(path: String, to localURL: URL) async throws {
        try await request(method: "GET", path: path).write(to: localURL, options: .atomic)
    }

    func upload(localURL: URL, to path: String) async throws {
        _ = try await request(method: "PUT", path: path, body: Data(contentsOf: localURL))
    }

    func createDirectory(path: String) async throws {
        _ = try await request(method: "PUT", path: path.hasSuffix("/") ? path : path + "/", body: Data())
    }

    func move(from: String, to: String) async throws {
        _ = try await request(method: "PUT", path: to, headers: ["x-amz-copy-source": "/\(source.bucket)\(from.normalizedRemotePath)"])
        try await delete(path: from)
    }

    func delete(path: String) async throws { _ = try await request(method: "DELETE", path: path) }

    private func request(method: String, path: String, query: [URLQueryItem] = [],
                         headers: [String: String] = [:], body: Data = Data()) async throws -> Data {
        try await requestWithResponse(method: method, path: path, query: query, headers: headers, body: body).0
    }

    private func requestWithResponse(method: String, path: String, query: [URLQueryItem] = [],
                                     headers: [String: String] = [:], body: Data = Data())
    async throws -> (Data, HTTPURLResponse) {
        guard var components = URLComponents(string: source.endpoint) else {
            throw RemoteError.invalidConfiguration("S3 endpoint 无效")
        }
        components.path = "/" + source.bucket + path.normalizedRemotePath
        let sortedQuery = query.sorted {
            if $0.name == $1.name { return ($0.value ?? "") < ($1.value ?? "") }
            return $0.name < $1.name
        }
        components.percentEncodedQuery = sortedQuery.isEmpty ? nil : sortedQuery.map {
            "\(Self.awsEncode($0.name))=\(Self.awsEncode($0.value ?? ""))"
        }.joined(separator: "&")
        guard let url = components.url else { throw RemoteError.invalidConfiguration("S3 URL 无效") }

        let now = Date()
        let stamp = Self.timestamp(now)
        let day = String(stamp.prefix(8))
        let payloadHash = Self.sha256(body)
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body.isEmpty ? nil : body
        request.setValue(stamp, forHTTPHeaderField: "x-amz-date")
        request.setValue(payloadHash, forHTTPHeaderField: "x-amz-content-sha256")
        headers.forEach { request.setValue($1, forHTTPHeaderField: $0) }

        var headerValues = [
            "host": "\(url.host ?? "")\(url.port.map { ":\($0)" } ?? "")",
            "x-amz-content-sha256": payloadHash,
            "x-amz-date": stamp
        ]
        headers.forEach { headerValues[$0.lowercased()] = $1 }
        let signedHeaderNames = headerValues.keys.sorted()
        let canonicalHeaders = signedHeaderNames.map { "\($0):\(headerValues[$0]!)\n" }.joined()
        let canonical = [
            method, components.percentEncodedPath, components.percentEncodedQuery ?? "", canonicalHeaders,
            signedHeaderNames.joined(separator: ";"), payloadHash
        ].joined(separator: "\n")
        let scope = "\(day)/\(source.region)/s3/aws4_request"
        let stringToSign = "AWS4-HMAC-SHA256\n\(stamp)\n\(scope)\n\(Self.sha256(Data(canonical.utf8)))"
        let signature = Self.hmac(Data(stringToSign.utf8), key:
            Self.hmac(Data("aws4_request".utf8), key:
                Self.hmac(Data("s3".utf8), key:
                    Self.hmac(Data(source.region.utf8), key:
                        Self.hmac(Data(day.utf8), key: Data(("AWS4" + secret).utf8))))))
            .map { String(format: "%02x", $0) }.joined()
        request.setValue(
            "AWS4-HMAC-SHA256 Credential=\(source.username)/\(scope), SignedHeaders=\(signedHeaderNames.joined(separator: ";")), Signature=\(signature)",
            forHTTPHeaderField: "Authorization"
        )
        let (data, rawResponse) = try await URLSession.shared.data(for: request)
        guard let response = rawResponse as? HTTPURLResponse, (200...299).contains(response.statusCode) else {
            throw RemoteError.server((rawResponse as? HTTPURLResponse)?.statusCode ?? 0,
                                     String(data: data, encoding: .utf8) ?? "")
        }
        return (data, response)
    }

    private static func sha256(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
    private static func hmac(_ data: Data, key: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: data, using: SymmetricKey(data: key)))
    }
    private static func timestamp(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyyMMdd'T'HHmmss'Z'"
        return formatter.string(from: date)
    }

    private static func awsEncode(_ value: String) -> String {
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }
}

private enum S3ListParser {
    static func parse(data: Data, prefix: String) -> [RemoteItem] {
        let xml = String(data: data, encoding: .utf8) ?? ""
        let keys = captures("(?s)<Key>(.*?)</Key>", in: xml)
        let prefixes = captures("(?s)<CommonPrefixes>.*?<Prefix>(.*?)</Prefix>.*?</CommonPrefixes>", in: xml)
        let files = keys.filter { !$0.hasSuffix("/") }.map {
            RemoteItem(name: URL(fileURLWithPath: $0).lastPathComponent, path: "/" + $0,
                       isDirectory: false, size: 0, modifiedAt: nil, version: $0)
        }
        let folders = prefixes.map {
            let clean = $0.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            return RemoteItem(name: URL(fileURLWithPath: clean).lastPathComponent, path: "/" + clean,
                              isDirectory: true, size: 0, modifiedAt: nil, version: clean)
        }
        return folders + files
    }

    private static func captures(_ pattern: String, in value: String) -> [String] {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }
        return regex.matches(in: value, range: NSRange(value.startIndex..., in: value)).compactMap {
            guard let range = Range($0.range(at: 1), in: value) else { return nil }
            return String(value[range])
                .replacingOccurrences(of: "&amp;", with: "&")
                .removingPercentEncoding ?? String(value[range])
        }
    }
}

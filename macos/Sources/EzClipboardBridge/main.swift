import AppKit
import CryptoKit
import Foundation
import Network

private let protocolAAD = Data("ez-clipboard-v1".utf8)
private let serviceType = "_ezclip._tcp"
private let mqttKeySalt = "ez-clipboard-mqtt-v1::c53e2b1394a74c83b5ad7f61d28e04cc"
private let mqttBrokerAddress = "b01a87f3.ala.cn-hangzhou.emqxsl.cn:8883"

struct BridgeConfiguration: Codable {
    var port: UInt16
    var privateKey: String?
    var mqttUsername: String?
    var mqttPassword: String?
    var mqttDeviceId: String?

    static func loadOrCreate() throws -> BridgeConfiguration {
        let directory = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent(".ez-clipboard", isDirectory: true)
        let file = directory.appendingPathComponent("config.json")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        var configuration = (try? Data(contentsOf: file))
            .flatMap { try? JSONDecoder().decode(BridgeConfiguration.self, from: $0) }
            ?? BridgeConfiguration(
                port: 42424,
                privateKey: nil,
                mqttUsername: nil,
                mqttPassword: nil,
                mqttDeviceId: nil
            )
        if configuration.privateKey.flatMap({ Data(base64Encoded: $0) })?.count != 32 {
            configuration.privateKey = Curve25519.KeyAgreement.PrivateKey()
                .rawRepresentation.base64EncodedString()
        }
        if configuration.mqttDeviceId?.isEmpty != false {
            configuration.mqttDeviceId = UUID().uuidString.lowercased()
        }
        try JSONEncoder().encode(configuration).write(to: file, options: [.atomic])
        return configuration
    }

    func save() throws {
        let directory = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent(".ez-clipboard", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try JSONEncoder().encode(self)
            .write(to: directory.appendingPathComponent("config.json"), options: [.atomic])
    }
}

struct ClipboardHistoryItem: Codable {
    let id: String
    let text: String
    let updatedAt: Int64
    var starred: Bool
}

final class ClipboardHistoryStore {
    private let file: URL
    private(set) var items: [ClipboardHistoryItem] = []
    var onChange: (([ClipboardHistoryItem]) -> Void)?

    init() {
        let directory = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent(".ez-clipboard", isDirectory: true)
        file = directory.appendingPathComponent("history.json")
        if let data = try? Data(contentsOf: file),
           let saved = try? JSONDecoder().decode([ClipboardHistoryItem].self, from: data) {
            items = Self.sortedAndTrimmed(saved)
        }
    }

    func add(id: String, text: String, updatedAt: Int64) {
        let existing = items.first { $0.id == id }
        let item = ClipboardHistoryItem(
            id: id,
            text: text,
            updatedAt: updatedAt,
            starred: existing?.starred ?? false
        )
        save(items.filter { $0.id != id } + [item])
    }

    func toggleStar(id: String) {
        save(items.map {
            guard $0.id == id else { return $0 }
            var changed = $0
            changed.starred.toggle()
            return changed
        })
    }

    func clearRegular() {
        save(items.filter(\.starred))
    }

    private func save(_ value: [ClipboardHistoryItem]) {
        items = Self.sortedAndTrimmed(value)
        if let data = try? JSONEncoder().encode(items) {
            try? data.write(to: file, options: [.atomic])
        }
        onChange?(items)
    }

    private static func sortedAndTrimmed(
        _ items: [ClipboardHistoryItem]
    ) -> [ClipboardHistoryItem] {
        let starred = items.filter(\.starred).sorted { $0.updatedAt > $1.updatedAt }
        let regular = items.filter { !$0.starred }
            .sorted { $0.updatedAt > $1.updatedAt }
            .prefix(100)
        return starred + regular
    }
}

final class StatusMenu {
    private let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let statusItem = NSMenuItem(title: "正在启动…", action: nil, keyEquivalent: "")
    private let historyMenu = NSMenu()
    private let historyStore: ClipboardHistoryStore
    private let mqttSettingsWindow: MqttSettingsWindowController

    init(
        historyStore: ClipboardHistoryStore,
        configuration: BridgeConfiguration,
        onSaveMqtt: @escaping (String, String) -> Void
    ) {
        self.historyStore = historyStore
        mqttSettingsWindow = MqttSettingsWindowController(
            username: configuration.mqttUsername ?? "",
            password: configuration.mqttPassword ?? "",
            onSave: onSaveMqtt
        )
        item.button?.image = NSImage(
            systemSymbolName: "rectangle.on.rectangle.angled",
            accessibilityDescription: "拾光剪切板"
        )
        let menu = NSMenu()
        menu.addItem(NSMenuItem(title: "拾光剪切板", action: nil, keyEquivalent: ""))
        menu.addItem(.separator())
        menu.addItem(statusItem)
        menu.addItem(.separator())
        let mqttItem = NSMenuItem(
            title: "远程同步设置…",
            action: #selector(openMqttSettings),
            keyEquivalent: ","
        )
        mqttItem.target = self
        menu.addItem(mqttItem)
        menu.addItem(.separator())
        let historyItem = NSMenuItem(title: "共享历史", action: nil, keyEquivalent: "")
        historyItem.submenu = historyMenu
        menu.addItem(historyItem)
        menu.addItem(.separator())
        let quit = NSMenuItem(title: "退出", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        quit.target = NSApplication.shared
        menu.addItem(quit)
        item.menu = menu
        historyStore.onChange = { [weak self] _ in
            DispatchQueue.main.async { self?.refreshHistory() }
        }
        refreshHistory()
    }

    func update(_ text: String) {
        NSLog("[EzClipboard] %@", text)
        DispatchQueue.main.async { [weak self] in self?.statusItem.title = text }
    }

    @objc private func openMqttSettings() {
        mqttSettingsWindow.show()
    }

    private func refreshHistory() {
        historyMenu.removeAllItems()
        let clear = NSMenuItem(
            title: "清除所有历史…",
            action: #selector(clearHistory),
            keyEquivalent: ""
        )
        clear.target = self
        clear.isEnabled = historyStore.items.contains { !$0.starred }
        historyMenu.addItem(clear)
        historyMenu.addItem(.separator())
        if historyStore.items.isEmpty {
            historyMenu.addItem(
                NSMenuItem(title: "同步过的文本会出现在这里", action: nil, keyEquivalent: "")
            )
            return
        }
        for history in historyStore.items {
            let title = history.text
                .replacingOccurrences(of: "\n", with: " ")
                .prefix(42)
            let item = NSMenuItem(
                title: "\(history.starred ? "★" : "☆") \(title)",
                action: nil,
                keyEquivalent: ""
            )
            let actions = NSMenu()
            let copy = NSMenuItem(
                title: "复制到剪切板",
                action: #selector(copyHistory(_:)),
                keyEquivalent: ""
            )
            copy.target = self
            copy.representedObject = history.text
            actions.addItem(copy)
            let star = NSMenuItem(
                title: history.starred ? "★ 取消收藏" : "☆ 收藏",
                action: #selector(toggleHistoryStar(_:)),
                keyEquivalent: ""
            )
            star.target = self
            star.representedObject = history.id
            actions.addItem(star)
            item.submenu = actions
            historyMenu.addItem(item)
        }
    }

    @objc private func clearHistory() {
        let alert = NSAlert()
        alert.messageText = "清除共享历史？"
        alert.informativeText = "将清除所有未收藏的记录，已收藏内容会保留。"
        alert.alertStyle = .warning
        alert.addButton(withTitle: "确认清除")
        alert.addButton(withTitle: "取消")
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        historyStore.clearRegular()
    }

    @objc private func copyHistory(_ sender: NSMenuItem) {
        guard let text = sender.representedObject as? String else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }

    @objc private func toggleHistoryStar(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String else { return }
        historyStore.toggleStar(id: id)
    }
}

final class MqttSettingsWindowController: NSWindowController, NSWindowDelegate {
    init(username: String, password: String, onSave: @escaping (String, String) -> Void) {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 440, height: 286),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        window.title = "远程同步设置"
        window.isReleasedWhenClosed = false
        super.init(window: window)
        window.contentView = MqttSettingsView(
            username: username,
            password: password,
            onSave: { [weak window] savedUsername, savedPassword in
                onSave(savedUsername, savedPassword)
                window?.close()
            }
        )
        window.center()
    }

    required init?(coder: NSCoder) {
        nil
    }

    func show() {
        guard let window else { return }
        NSApplication.shared.activate(ignoringOtherApps: true)
        showWindow(nil)
        window.makeKeyAndOrderFront(nil)
    }
}

final class MqttSettingsView: NSView {
    private let usernameField = NSTextField()
    private let passwordField = NSSecureTextField()
    private let onSave: (String, String) -> Void

    init(username: String, password: String, onSave: @escaping (String, String) -> Void) {
        self.onSave = onSave
        super.init(frame: NSRect(x: 0, y: 0, width: 440, height: 286))

        let title = NSTextField(labelWithString: "跨网络同步")
        title.font = .boldSystemFont(ofSize: 20)
        title.frame = NSRect(x: 28, y: 235, width: 384, height: 26)
        addSubview(title)

        let detail = NSTextField(
            wrappingLabelWithString: "手机和电脑填写相同的用户名、密码即可。传输内容会在发送前加密。"
        )
        detail.textColor = .secondaryLabelColor
        detail.frame = NSRect(x: 28, y: 196, width: 384, height: 34)
        addSubview(detail)

        usernameField.placeholderString = "MQTT 用户名"
        usernameField.stringValue = username
        usernameField.frame = NSRect(x: 28, y: 145, width: 384, height: 28)
        addSubview(usernameField)

        passwordField.placeholderString = "MQTT 密码"
        passwordField.stringValue = password
        passwordField.frame = NSRect(x: 28, y: 103, width: 384, height: 28)
        addSubview(passwordField)

        let server = NSTextField(
            labelWithString: "服务器  \(mqttBrokerAddress)"
        )
        server.textColor = .tertiaryLabelColor
        server.font = .systemFont(ofSize: 11)
        server.frame = NSRect(x: 28, y: 70, width: 384, height: 18)
        addSubview(server)

        let saveButton = NSButton(title: "保存设置", target: self, action: #selector(save))
        saveButton.bezelStyle = .rounded
        saveButton.keyEquivalent = "\r"
        saveButton.frame = NSRect(x: 292, y: 24, width: 120, height: 32)
        addSubview(saveButton)
    }

    required init?(coder: NSCoder) {
        nil
    }

    @objc private func save() {
        onSave(usernameField.stringValue, passwordField.stringValue)
    }
}

final class ClipboardBridge {
    private let privateKey: Curve25519.KeyAgreement.PrivateKey
    private let queue = DispatchQueue(label: "ez.clipboard.bridge.network")
    private let listener: NWListener
    private let onStatus: (String) -> Void
    private let historyStore: ClipboardHistoryStore
    private var connection: NWConnection?
    private var receiveBuffer = Data()
    private var sessionKey: SymmetricKey?
    private var peerName: String?
    private var latestClipboardAt: Int64 = 0
    private var lastPasteboardChange = NSPasteboard.general.changeCount
    private var lastRemoteText: String?
    private var clipboardTimer: Timer?
    private var heartbeatTimer: DispatchSourceTimer?
    private var mqttClient: MqttClient!
    private var mqttUsername: String
    private var mqttPassword: String
    private let mqttDeviceId: String
    private var mqttConnected = false
    private var mqttFallbackWork: DispatchWorkItem?
    private var processedMessageIds = Set<String>()

    init(
        configuration: BridgeConfiguration,
        historyStore: ClipboardHistoryStore,
        onStatus: @escaping (String) -> Void
    ) throws {
        guard
            let privateKeyData = configuration.privateKey.flatMap({ Data(base64Encoded: $0) }),
            let key = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateKeyData)
        else { throw BridgeError.invalidIdentity }
        privateKey = key
        self.historyStore = historyStore
        self.onStatus = onStatus
        mqttUsername = configuration.mqttUsername ?? ""
        mqttPassword = configuration.mqttPassword ?? ""
        mqttDeviceId = configuration.mqttDeviceId ?? UUID().uuidString.lowercased()
        listener = try NWListener(using: .tcp, on: NWEndpoint.Port(rawValue: configuration.port)!)
        listener.service = NWListener.Service(
            name: Host.current().localizedName ?? "Mac",
            type: serviceType
        )
        mqttClient = MqttClient(
            clientID: "ez-mac-\(mqttDeviceId)",
            onMessage: { [weak self] data in
                self?.queue.async { self?.receiveMqtt(data) }
            },
            onState: { [weak self] connected, detail in
                guard let self else { return }
                self.queue.async {
                    self.mqttConnected = connected
                    if self.sessionKey != nil {
                        if connected { self.mqttClient.stop() }
                        return
                    }
                    self.onStatus("MQTT · \(detail)")
                }
            }
        )
    }

    func start() {
        listener.newConnectionHandler = { [weak self] in self?.accept($0) }
        listener.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.onStatus("等待手机自动连接")
            case .failed(let error):
                self?.onStatus("启动失败：\(error.localizedDescription)")
            default:
                break
            }
        }
        listener.start(queue: queue)
        startMqttFallback(after: 2)
        DispatchQueue.main.async { [weak self] in
            self?.clipboardTimer = Timer.scheduledTimer(withTimeInterval: 0.8, repeats: true) {
                [weak self] _ in self?.checkMacClipboard()
            }
        }
    }

    func updateMqtt(username: String, password: String) {
        queue.async {
            self.mqttUsername = username
            self.mqttPassword = password
            if self.sessionKey == nil {
                self.startMqttFallback(after: 0)
            } else {
                self.mqttClient.stop()
            }
        }
    }

    private func accept(_ newConnection: NWConnection) {
        connection?.cancel()
        heartbeatTimer?.cancel()
        connection = newConnection
        sessionKey = nil
        peerName = nil
        receiveBuffer.removeAll(keepingCapacity: true)
        newConnection.stateUpdateHandler = { [weak self, weak newConnection] state in
            guard let self, let newConnection, newConnection === self.connection else { return }
            switch state {
            case .ready:
                self.receive(on: newConnection)
            case .failed, .cancelled:
                self.resetConnection(newConnection)
            default:
                break
            }
        }
        newConnection.start(queue: queue)
    }

    private func receive(on activeConnection: NWConnection) {
        activeConnection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) {
            [weak self, weak activeConnection] data, _, isComplete, error in
            guard let self, let activeConnection, activeConnection === self.connection else { return }
            if let data {
                self.receiveBuffer.append(data)
                self.consumeLines()
            }
            if isComplete || error != nil {
                self.resetConnection(activeConnection)
            } else {
                self.receive(on: activeConnection)
            }
        }
    }

    private func consumeLines() {
        while let newline = receiveBuffer.firstIndex(of: 0x0A) {
            let line = receiveBuffer[..<newline]
            receiveBuffer.removeSubrange(...newline)
            guard
                !line.isEmpty,
                let object = try? JSONSerialization.jsonObject(with: Data(line)) as? [String: Any],
                let type = object["type"] as? String
            else { continue }
            switch type {
            case "hello":
                establishSecureSession(object)
            case "clip" where sessionKey != nil:
                receiveClipboard(object, key: sessionKey!, source: peerName ?? "Android")
            case "pong":
                break
            default:
                break
            }
        }
    }

    private func establishSecureSession(_ message: [String: Any]) {
        guard
            let keyText = message["publicKey"] as? String,
            let keyData = Data(base64Encoded: keyText),
            let peerKey = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: keyData),
            let sharedSecret = try? privateKey.sharedSecretFromKeyAgreement(with: peerKey)
        else {
            connection?.cancel()
            return
        }
        sessionKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: protocolAAD,
            sharedInfo: Data("clipboard-key".utf8),
            outputByteCount: 32
        )
        peerName = message["device"] as? String
        mqttFallbackWork?.cancel()
        mqttFallbackWork = nil
        mqttConnected = false
        mqttClient.stop()
        send([
            "type": "hello_ack",
            "device": Host.current().localizedName ?? "Mac",
            "publicKey": privateKey.publicKey.rawRepresentation.base64EncodedString(),
        ])
        onStatus("局域网 · 已连接 \(peerName ?? "Android")")
        startHeartbeat()
    }

    private func receiveClipboard(
        _ message: [String: Any],
        key: SymmetricKey,
        source: String
    ) {
        guard
            let id = message["id"] as? String,
            let updatedAt = message["updatedAt"] as? Int64,
            !processedMessageIds.contains(id),
            updatedAt > latestClipboardAt,
            let nonceText = message["nonce"] as? String,
            let dataText = message["data"] as? String,
            let nonceData = Data(base64Encoded: nonceText),
            let encrypted = Data(base64Encoded: dataText),
            encrypted.count >= 16
        else { return }
        do {
            let sealed = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: nonceData),
                ciphertext: encrypted.dropLast(16),
                tag: encrypted.suffix(16)
            )
            let clear = try AES.GCM.open(sealed, using: key, authenticating: protocolAAD)
            guard let text = String(data: clear, encoding: .utf8) else { return }
            processedMessageIds.insert(id)
            trimProcessedMessageIds()
            latestClipboardAt = updatedAt
            historyStore.add(id: id, text: text, updatedAt: updatedAt)
            DispatchQueue.main.async { [weak self] in self?.writeMacClipboard(text) }
            let channel = source == "另一台设备" ? "MQTT" : "局域网"
            onStatus("\(channel) · 已接收剪切板")
        } catch {
            onStatus("收到无法解密的内容")
        }
    }

    private func receiveMqtt(_ data: Data) {
        guard
            let message = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            message["senderId"] as? String != mqttDeviceId,
            !mqttUsername.isEmpty,
            !mqttPassword.isEmpty
        else { return }
        receiveClipboard(
            message,
            key: mqttEncryptionKey(username: mqttUsername, password: mqttPassword),
            source: "另一台设备"
        )
    }

    private func checkMacClipboard() {
        let pasteboard = NSPasteboard.general
        guard pasteboard.changeCount != lastPasteboardChange else { return }
        lastPasteboardChange = pasteboard.changeCount
        guard let text = pasteboard.string(forType: .string), !text.isEmpty else { return }
        if text == lastRemoteText {
            lastRemoteText = nil
            return
        }
        sendClipboard(text)
    }

    private func writeMacClipboard(_ text: String) {
        lastRemoteText = text
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
        lastPasteboardChange = pasteboard.changeCount
    }

    private func sendClipboard(_ text: String) {
        guard
            text.utf8.count <= 256 * 1024,
            sessionKey != nil || mqttConnected
        else { return }
        let updatedAt = Int64(Date().timeIntervalSince1970 * 1000)
        let id = UUID().uuidString.lowercased()
        if let key = sessionKey, let message = encryptedMessage(
            text: text, key: key, id: id, updatedAt: updatedAt, senderId: nil
        ) {
            send(message)
        }
        if mqttConnected, !mqttUsername.isEmpty, !mqttPassword.isEmpty,
           let message = encryptedMessage(
               text: text,
               key: mqttEncryptionKey(username: mqttUsername, password: mqttPassword),
               id: id,
               updatedAt: updatedAt,
               senderId: mqttDeviceId
           ),
           let data = try? JSONSerialization.data(withJSONObject: message) {
            mqttClient.publish(data)
        }
        processedMessageIds.insert(id)
        trimProcessedMessageIds()
        latestClipboardAt = max(latestClipboardAt, updatedAt)
        historyStore.add(id: id, text: text, updatedAt: updatedAt)
    }

    private func encryptedMessage(
        text: String,
        key: SymmetricKey,
        id: String,
        updatedAt: Int64,
        senderId: String?
    ) -> [String: Any]? {
        guard let sealed = try? AES.GCM.seal(
            Data(text.utf8),
            using: key,
            authenticating: protocolAAD
        ) else { return nil }
        var message: [String: Any] = [
            "type": "clip",
            "id": id,
            "updatedAt": updatedAt,
            "nonce": Data(sealed.nonce).base64EncodedString(),
            "data": (sealed.ciphertext + sealed.tag).base64EncodedString(),
        ]
        if let senderId { message["senderId"] = senderId }
        return message
    }

    private func trimProcessedMessageIds() {
        if processedMessageIds.count > 256 {
            processedMessageIds.removeAll()
        }
    }

    private func send(_ object: [String: Any]) {
        guard
            let connection,
            let data = try? JSONSerialization.data(withJSONObject: object)
        else { return }
        connection.send(
            content: data + Data([0x0A]),
            completion: .contentProcessed { [weak self, weak connection] error in
                guard let self, let connection, error != nil else { return }
                self.queue.async { self.resetConnection(connection) }
            }
        )
    }

    private func startHeartbeat() {
        heartbeatTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 5, repeating: 5)
        timer.setEventHandler { [weak self] in self?.send(["type": "ping"]) }
        timer.resume()
        heartbeatTimer = timer
    }

    private func resetConnection(_ oldConnection: NWConnection) {
        guard oldConnection === connection else { return }
        heartbeatTimer?.cancel()
        heartbeatTimer = nil
        connection = nil
        sessionKey = nil
        peerName = nil
        onStatus("局域网已断开，正在启用 MQTT")
        startMqttFallback(after: 0)
    }

    private func startMqttFallback(after delay: TimeInterval) {
        guard sessionKey == nil, !mqttUsername.isEmpty, !mqttPassword.isEmpty else { return }
        mqttFallbackWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.sessionKey == nil else { return }
            self.onStatus("MQTT · 正在连接，同时等待局域网")
            self.mqttClient.start(username: self.mqttUsername, password: self.mqttPassword)
        }
        mqttFallbackWork = work
        queue.asyncAfter(deadline: .now() + delay, execute: work)
    }
}

enum BridgeError: LocalizedError {
    case invalidIdentity
    var errorDescription: String? { "电脑身份配置无效" }
}

private func mqttEncryptionKey(username: String, password: String) -> SymmetricKey {
    let seed = SymmetricKey(data: Data("\(username)\0\(password)".utf8))
    return HKDF<SHA256>.deriveKey(
        inputKeyMaterial: seed,
        salt: Data("\(mqttKeySalt)|\(username)".utf8),
        info: Data("clipboard-key".utf8),
        outputByteCount: 32
    )
}

do {
    NSApplication.shared.setActivationPolicy(.accessory)
    let historyStore = ClipboardHistoryStore()
    var configuration = try BridgeConfiguration.loadOrCreate()
    var bridge: ClipboardBridge?
    let menu = StatusMenu(
        historyStore: historyStore,
        configuration: configuration,
        onSaveMqtt: { username, password in
            configuration.mqttUsername = username.trimmingCharacters(in: .whitespacesAndNewlines)
            configuration.mqttPassword = password
            try? configuration.save()
            bridge?.updateMqtt(
                username: configuration.mqttUsername ?? "",
                password: configuration.mqttPassword ?? ""
            )
        }
    )
    bridge = try ClipboardBridge(
        configuration: configuration,
        historyStore: historyStore,
        onStatus: menu.update
    )
    bridge?.start()
    withExtendedLifetime((menu, bridge)) {
        NSApplication.shared.run()
    }
} catch {
    let alert = NSAlert()
    alert.messageText = "拾光剪切板无法启动"
    alert.informativeText = error.localizedDescription
    alert.runModal()
    exit(1)
}

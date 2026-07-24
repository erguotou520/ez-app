import Foundation
import Network

private let mqttHost = "b01a87f3.ala.cn-hangzhou.emqxsl.cn"
private let mqttPort: NWEndpoint.Port = 8883

final class MqttClient {
    private let queue = DispatchQueue(label: "ez.clipboard.mqtt")
    private let clientID: String
    private let onMessage: (Data) -> Void
    private let onState: (Bool, String) -> Void
    private var username = ""
    private var password = ""
    private var topic = ""
    private var connection: NWConnection?
    private var buffer = Data()
    private var packetID: UInt16 = 1
    private var stopped = true
    private var reconnectWork: DispatchWorkItem?
    private var pingTimer: DispatchSourceTimer?
    private var activeCredentials: String?

    init(
        clientID: String,
        onMessage: @escaping (Data) -> Void,
        onState: @escaping (Bool, String) -> Void
    ) {
        self.clientID = clientID
        self.onMessage = onMessage
        self.onState = onState
    }

    func start(username: String, password: String) {
        queue.async {
            let credentials = "\(username)\0\(password)"
            if !self.stopped, self.activeCredentials == credentials { return }
            self.stopLocked()
            guard !username.isEmpty, !password.isEmpty else {
                self.onState(false, "MQTT 尚未配置")
                return
            }
            self.username = username
            self.password = password
            self.topic = mqttTopic(username: username)
            self.activeCredentials = credentials
            self.stopped = false
            self.connect()
        }
    }

    func stop() {
        queue.async { self.stopLocked() }
    }

    func publish(_ payload: Data) {
        queue.async {
            guard self.connection != nil else { return }
            let id = self.nextPacketID()
            var body = mqttString(self.topic)
            body += Data([UInt8(id >> 8), UInt8(id & 0xff)])
            body += payload
            self.sendPacket(header: 0x32, body: body)
        }
    }

    private func connect() {
        guard !stopped else { return }
        let tls = NWProtocolTLS.Options()
        let parameters = NWParameters(tls: tls, tcp: NWProtocolTCP.Options())
        let connection = NWConnection(host: NWEndpoint.Host(mqttHost), port: mqttPort, using: parameters)
        self.connection = connection
        buffer.removeAll(keepingCapacity: true)
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection, connection === self.connection else { return }
            switch state {
            case .ready:
                self.sendConnect()
                self.receive()
            case .failed:
                self.onState(false, "远程通道正在重连")
                self.scheduleReconnect()
            case .cancelled:
                if !self.stopped { self.scheduleReconnect() }
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    private func sendConnect() {
        var body = mqttString("MQTT")
        body += Data([0x04, 0xC2, 0x00, 0x2D])
        body += mqttString(clientID)
        body += mqttString(username)
        body += mqttString(password)
        sendPacket(header: 0x10, body: body)
    }

    private func subscribe() {
        let id = nextPacketID()
        var body = Data([UInt8(id >> 8), UInt8(id & 0xff)])
        body += mqttString(topic)
        body += Data([0x01])
        sendPacket(header: 0x82, body: body)
        startPing()
    }

    private func receive() {
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) {
            [weak self] data, _, complete, error in
            guard let self else { return }
            if let data {
                self.buffer += data
                self.consumePackets()
            }
            if complete || error != nil {
                self.onState(false, "远程通道正在重连")
                self.scheduleReconnect()
            } else {
                self.receive()
            }
        }
    }

    private func consumePackets() {
        while buffer.count >= 2 {
            var multiplier = 1
            var remaining = 0
            var index = 1
            var completeLength = false
            while index < buffer.count && index <= 4 {
                let byte = Int(buffer[index])
                remaining += (byte & 127) * multiplier
                index += 1
                if byte & 128 == 0 {
                    completeLength = true
                    break
                }
                multiplier *= 128
            }
            guard completeLength, buffer.count >= index + remaining else { return }
            let header = buffer[0]
            let body = Data(buffer[index..<(index + remaining)])
            buffer.removeSubrange(0..<(index + remaining))
            handlePacket(header: header, body: body)
        }
    }

    private func handlePacket(header: UInt8, body: Data) {
        switch header >> 4 {
        case 2:
            guard body.count >= 2, body[1] == 0 else {
                onState(false, "账号或密码错误")
                connection?.cancel()
                return
            }
            subscribe()
            onState(true, "远程通道已连接")
        case 3:
            guard body.count >= 2 else { return }
            let topicLength = Int(body[0]) << 8 | Int(body[1])
            let qos = (header >> 1) & 0x03
            var payloadIndex = 2 + topicLength
            guard body.count >= payloadIndex else { return }
            if qos > 0 {
                guard body.count >= payloadIndex + 2 else { return }
                let id = Data(body[payloadIndex..<(payloadIndex + 2)])
                payloadIndex += 2
                if qos == 1 { sendPacket(header: 0x40, body: id) }
            }
            onMessage(Data(body.dropFirst(payloadIndex)))
        default:
            break
        }
    }

    private func sendPacket(header: UInt8, body: Data) {
        guard let connection else { return }
        var packet = Data([header])
        packet += mqttRemainingLength(body.count)
        packet += body
        connection.send(content: packet, completion: .contentProcessed { _ in })
    }

    private func startPing() {
        pingTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 30, repeating: 30)
        timer.setEventHandler { [weak self] in self?.sendPacket(header: 0xC0, body: Data()) }
        timer.resume()
        pingTimer = timer
    }

    private func scheduleReconnect() {
        guard !stopped else { return }
        connection?.cancel()
        connection = nil
        pingTimer?.cancel()
        reconnectWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.connect() }
        reconnectWork = work
        queue.asyncAfter(deadline: .now() + 3, execute: work)
    }

    private func stopLocked() {
        stopped = true
        reconnectWork?.cancel()
        reconnectWork = nil
        pingTimer?.cancel()
        pingTimer = nil
        connection?.cancel()
        connection = nil
        buffer.removeAll()
        activeCredentials = nil
    }

    private func nextPacketID() -> UInt16 {
        packetID = packetID == UInt16.max ? 1 : packetID + 1
        return packetID
    }
}

func mqttTopic(username: String) -> String {
    let channel = Data(username.utf8).base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: "")
    return "ez-clipboard/v1/u/\(channel)/clips"
}

private func mqttString(_ value: String) -> Data {
    let data = Data(value.utf8)
    return Data([UInt8(data.count >> 8), UInt8(data.count & 0xff)]) + data
}

private func mqttRemainingLength(_ length: Int) -> Data {
    var value = length
    var result = Data()
    repeat {
        var byte = UInt8(value % 128)
        value /= 128
        if value > 0 { byte |= 128 }
        result.append(byte)
    } while value > 0
    return result
}

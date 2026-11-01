import Foundation

public enum WireProtocol {
    /// Bump when the wire format changes incompatibly.
    public static let version = 1
}

/// Message kinds. Payloads carry content IDs and numbers, never translated strings.
public enum MessageType: String, Codable, Sendable {
    case hello            // client -> host: join request + versions
    case welcome          // host -> client: accepted, room info
    case reject           // host -> client: version mismatch / room full
    case ping, pong       // RTT measurement
    case lobbyState       // host -> all: players + config + content locale
    case publicState      // host -> all: public view model
    case privateState     // host -> one player: private view model
    case input            // client -> host: tap / vote / answer / motion summary
    case report           // client -> host: UGC report
    case kick             // host -> client
    case end              // host -> all: game over / host leaving
}

public enum RejectReason: String, Codable, Sendable {
    case protocolMismatch
    case contentPackMismatch
    case roomFull
    case gameInProgress
    case kicked
}

public struct Envelope: Codable, Equatable, Sendable {
    public var version: Int
    public var contentVersion: Int
    public var type: MessageType
    public var seq: Int
    /// Sender-local milliseconds. Only used for relative comparisons plus the host's offset estimate.
    public var timestampMs: Int64
    public var payload: Data

    public init(version: Int = WireProtocol.version,
                contentVersion: Int,
                type: MessageType,
                seq: Int,
                timestampMs: Int64,
                payload: Data = Data()) {
        self.version = version
        self.contentVersion = contentVersion
        self.type = type
        self.seq = seq
        self.timestampMs = timestampMs
        self.payload = payload
    }
}

public struct HelloPayload: Codable, Equatable, Sendable {
    public var playerID: PlayerID
    public var nickname: String
    public var avatar: Avatar
    public var uiLocale: String
    public init(playerID: PlayerID, nickname: String, avatar: Avatar, uiLocale: String) {
        self.playerID = playerID
        self.nickname = nickname
        self.avatar = avatar
        self.uiLocale = uiLocale
    }
}

public struct RejectPayload: Codable, Equatable, Sendable {
    public var reason: RejectReason
    public init(reason: RejectReason) { self.reason = reason }
}

public enum Codec {
    public static func encode<T: Encodable>(_ value: T) throws -> Data {
        let enc = JSONEncoder()
        enc.outputFormatting = [.sortedKeys]
        return try enc.encode(value)
    }

    public static func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        try JSONDecoder().decode(T.self, from: data)
    }
}

/// Handshake check: protocol version AND content pack version must both match.
public enum Handshake {
    public static func validate(clientProtocol: Int,
                                clientContentVersion: Int,
                                hostProtocol: Int = WireProtocol.version,
                                hostContentVersion: Int) -> RejectReason? {
        if clientProtocol != hostProtocol { return .protocolMismatch }
        if clientContentVersion != hostContentVersion { return .contentPackMismatch }
        return nil
    }
}

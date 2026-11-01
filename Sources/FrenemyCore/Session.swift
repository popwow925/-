import Foundation

// Transport glue. HostSession wraps the engine; ClientSession is used by player devices and by the
// offline demo bots. Both talk only through GameTransport, so the same code runs over
// MultipeerConnectivity (App target) and over the in-memory LoopbackTransport (FrenemyDemo).
// Peer IDs on the transport are PlayerIDs.

public enum InputPayload: Codable, Equatable, Sendable {
    case score(Int)
    case buyShield
    case playCard(CardKind, target: PlayerID)
    case bet(target: PlayerID, stake: Int)
    case awardVote(target: PlayerID)
    case awardAccept
    case awardDecline
    case awardReplace(titleID: String)
    case awardShow(Bool)
    case report(target: PlayerID, content: ReportedContent)
}

public struct WelcomePayload: Codable, Equatable, Sendable {
    public let playerID: PlayerID
    public let roomCode: String
    public let contentLocale: ContentLocale
    public let contentVersion: Int
}

struct PingPayload: Codable, Equatable {
    let sentMs: Int64
}

public enum WallClock {
    /// Wall clock in ms. Deliberately Date-based (not systemUptime / mach_absolute_time) so no
    /// "system boot time" Required Reason API is touched.
    public static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

// MARK: - Host

public final class HostSession {
    public let engine: GameEngine
    private let transport: GameTransport
    private let clock: () -> Int64
    private let filter: TextFilter?
    private var seq = 0
    private var disconnectedAt: [PlayerID: Int64] = [:]
    private var rttSamples: [PlayerID: [Int]] = [:]
    private var pingsSent: [PlayerID: Int] = [:]

    public static let pingsPerPeer = 5

    public init(engine: GameEngine,
                transport: GameTransport,
                filter: TextFilter? = nil,
                clock: @escaping () -> Int64 = WallClock.nowMs) {
        self.engine = engine
        self.transport = transport
        self.filter = filter
        self.clock = clock
        transport.onEvent = { [weak self] event in self?.handle(event) }
    }

    public func start() { transport.start() }
    public func stop() { transport.stop() }

    public func latency(of player: PlayerID) -> LatencyEstimate {
        LatencyEstimator.estimate(rttSamplesMs: rttSamples[player] ?? [])
    }

    /// Host "continue" (button or timer).
    @discardableResult
    public func step() -> Bool {
        let advanced = engine.step()
        broadcastState()
        if engine.phase == .ended { send(.end, to: nil, payload: Data()) }
        return advanced
    }

    public func kick(_ id: PlayerID) {
        engine.kick(id)
        send(.reject, to: id, payload: (try? Codec.encode(RejectPayload(reason: .kicked))) ?? Data())
        transport.disconnect(id)
        broadcastState()
    }

    /// Fired after every broadcast so a host UI (公共控制台 / TV) can refresh from `engine.publicState()`.
    public var onStateChanged: (() -> Void)?

    public func broadcastState() {
        if let data = try? Codec.encode(engine.publicState()) {
            send(.publicState, to: nil, payload: data)
        }
        for id in engine.connectedIDs {
            if let priv = engine.privateState(for: id), let data = try? Codec.encode(priv) {
                send(.privateState, to: id, payload: data)
            }
        }
        onStateChanged?()
    }

    // MARK: Incoming

    private func handle(_ event: TransportEvent) {
        switch event {
        case .peerConnected:
            break
        case .peerDisconnected(let id):
            engine.markConnected(id, false)
            disconnectedAt[id] = clock()
            broadcastState()
        case .received(let from, let env):
            handleMessage(from: from, env)
        }
    }

    private func handleMessage(from: PlayerID, _ env: Envelope) {
        switch env.type {
        case .hello:
            handleHello(from: from, env)
        case .pong:
            if let ping = try? Codec.decode(PingPayload.self, from: env.payload) {
                let rtt = max(0, Int(clock() - ping.sentMs))
                rttSamples[from, default: []].append(rtt)
                sendNextPing(to: from)
            }
        case .input, .report:
            guard let input = try? Codec.decode(InputPayload.self, from: env.payload) else { return }
            apply(input, from: from)
            broadcastState()
        default:
            break
        }
    }

    private func handleHello(from: PlayerID, _ env: Envelope) {
        guard let hello = try? Codec.decode(HelloPayload.self, from: env.payload), hello.playerID == from else {
            reject(from, .protocolMismatch)
            return
        }
        // Reconnect window.
        if let t = disconnectedAt[from], clock() - t > Int64(Limits.reconnectWindowSeconds * 1000) {
            reject(from, .gameInProgress)
            return
        }
        var nickname = hello.nickname
        if let f = filter {
            switch f.checkNickname(hello.nickname) {
            case .ok(let cleaned): nickname = cleaned
            case .empty, .tooLong, .blocked: nickname = "P\(engine.playerIDs.count + 1)"
            }
        }
        let player = Player(id: from, nickname: nickname, avatar: hello.avatar)
        if let reason = engine.join(player, protocolVersion: env.version, contentVersion: env.contentVersion) {
            reject(from, reason)
            return
        }
        disconnectedAt[from] = nil
        let welcome = WelcomePayload(playerID: from,
                                     roomCode: engine.roomCode,
                                     contentLocale: engine.config.contentLocale,
                                     contentVersion: engine.contentVersion)
        if let data = try? Codec.encode(welcome) { send(.welcome, to: from, payload: data) }
        rttSamples[from] = []
        pingsSent[from] = 0
        sendNextPing(to: from)
        broadcastState()
    }

    private func apply(_ input: InputPayload, from: PlayerID) {
        switch input {
        case .score(let s): engine.submitScore(from, s)
        case .buyShield: engine.buyShield(from)
        case .playCard(let card, let target): _ = engine.playCard(card, by: from, on: target)
        case .bet(let target, let stake): _ = engine.placeBet(Bet(bettor: from, target: target, stake: stake))
        case .awardVote(let target): _ = engine.voteAward(voter: from, target: target)
        case .awardAccept: _ = engine.awardAccept(from)
        case .awardDecline: _ = engine.awardDecline(from)
        case .awardReplace(let id): _ = engine.awardReplace(from, with: id)
        case .awardShow(let show): _ = engine.awardShowInReport(from, show)
        case .report(let target, let content): engine.submitReport(reporter: from, target: target, content: content)
        }
    }

    private func sendNextPing(to id: PlayerID) {
        let sent = pingsSent[id] ?? 0
        guard sent < HostSession.pingsPerPeer else { return }
        pingsSent[id] = sent + 1
        if let data = try? Codec.encode(PingPayload(sentMs: clock())) { send(.ping, to: id, payload: data) }
    }

    private func reject(_ id: PlayerID, _ reason: RejectReason) {
        send(.reject, to: id, payload: (try? Codec.encode(RejectPayload(reason: reason))) ?? Data())
        transport.disconnect(id)
    }

    private func send(_ type: MessageType, to peer: PlayerID?, payload: Data) {
        seq += 1
        let env = Envelope(contentVersion: engine.contentVersion, type: type, seq: seq, timestampMs: clock(), payload: payload)
        let reliability: Reliability = .reliable
        if let peer = peer {
            transport.send(env, to: peer, reliability: reliability)
        } else {
            transport.broadcast(env, reliability: reliability)
        }
    }
}

// MARK: - Client

public final class ClientSession {
    public let player: Player
    private let transport: GameTransport
    private let contentVersion: Int
    private let uiLocale: String
    private let clock: () -> Int64
    private var seq = 0
    private var hostPeer: PlayerID?

    public private(set) var welcome: WelcomePayload?
    public private(set) var publicState: PublicState?
    public private(set) var privateState: PrivateState?
    public private(set) var rejectReason: RejectReason?
    public private(set) var hasEnded = false

    public var onPublicState: ((PublicState) -> Void)?
    public var onPrivateState: ((PrivateState) -> Void)?
    public var onReject: ((RejectReason) -> Void)?
    public var onEnd: (() -> Void)?

    public init(player: Player,
                transport: GameTransport,
                contentVersion: Int,
                uiLocale: String,
                clock: @escaping () -> Int64 = WallClock.nowMs) {
        self.player = player
        self.transport = transport
        self.contentVersion = contentVersion
        self.uiLocale = uiLocale
        self.clock = clock
        transport.onEvent = { [weak self] event in self?.handle(event) }
    }

    public func start() { transport.start() }
    public func stop() { transport.stop() }

    public func send(_ input: InputPayload) {
        guard let host = hostPeer, let data = try? Codec.encode(input) else { return }
        transport.send(makeEnvelope(.input, data), to: host, reliability: .reliable)
    }

    private func makeEnvelope(_ type: MessageType, _ payload: Data) -> Envelope {
        seq += 1
        return Envelope(contentVersion: contentVersion, type: type, seq: seq, timestampMs: clock(), payload: payload)
    }

    private func handle(_ event: TransportEvent) {
        switch event {
        case .peerConnected(let id):
            hostPeer = id
            let hello = HelloPayload(playerID: player.id, nickname: player.nickname, avatar: player.avatar, uiLocale: uiLocale)
            if let data = try? Codec.encode(hello) {
                transport.send(makeEnvelope(.hello, data), to: id, reliability: .reliable)
            }
        case .peerDisconnected:
            if !hasEnded { hasEnded = true; onEnd?() }
        case .received(_, let env):
            handleMessage(env)
        }
    }

    private func handleMessage(_ env: Envelope) {
        switch env.type {
        case .welcome:
            welcome = try? Codec.decode(WelcomePayload.self, from: env.payload)
        case .reject:
            if let r = try? Codec.decode(RejectPayload.self, from: env.payload) {
                rejectReason = r.reason
                onReject?(r.reason)
            }
        case .ping:
            if let host = hostPeer {
                transport.send(makeEnvelope(.pong, env.payload), to: host, reliability: .reliable)
            }
        case .publicState:
            if let s = try? Codec.decode(PublicState.self, from: env.payload) {
                publicState = s
                onPublicState?(s)
            }
        case .privateState:
            if let s = try? Codec.decode(PrivateState.self, from: env.payload) {
                privateState = s
                onPrivateState?(s)
            }
        case .end:
            if !hasEnded { hasEnded = true; onEnd?() }
        default:
            break
        }
    }
}

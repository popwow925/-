import SwiftUI
import FrenemyCore
import FrenemyDemo

enum PlayMode: Equatable {
    case none
    case demo     // offline: this device is host + one player, bots fill the rest. No Multipeer objects.
    case host     // 公共控制台: shows PublicState only, is NOT a player (v1 decision)
    case client   // a player's phone: PublicState + its own PrivateState
}

/// Single source of truth for the UI. Everything here runs on the main thread: SwiftUI calls it from
/// the main thread and MultipeerTransport hops every callback to the main queue before it reaches us.
final class AppModel: ObservableObject {
    @Published private(set) var mode: PlayMode = .none
    @Published private(set) var publicState: PublicState?
    @Published private(set) var privateState: PrivateState?
    @Published private(set) var rooms: [DiscoveredRoom] = []
    @Published private(set) var connectedToRoom = false
    @Published var rejectReason: RejectReason?
    @Published var connectionEnded = false
    @Published var failureText: String?
    /// Device-only hide list (plan: 本机隐藏). Never sent anywhere, wiped when the game ends / on leave.
    @Published var hideList = LocalHideList()

    let profile = ProfileStore()
    let content: ContentLibrary?
    let contentProblem: String?
    let textFilter: TextFilter

    private var demo: DemoRoom?
    private var host: HostSession?
    private var hostTransport: MultipeerTransport?
    private var client: ClientSession?
    private var clientTransport: MultipeerTransport?

    init() {
        let loaded = ContentLoader.load()
        content = loaded.library
        contentProblem = loaded.problem
        textFilter = ContentLoader.loadBlocklist()
    }

    // MARK: Text

    /// Text for a content ID in the ROOM's locale (the host's choice), not the device language.
    func text(_ id: String) -> String {
        let locale = publicState?.contentLocale ?? .zhHans
        return content?.text(id, locale: locale) ?? id
    }

    func name(of id: PlayerID) -> String {
        guard let p = publicState?.players.first(where: { $0.id == id }) else { return id }
        if hideList.isHidden(id, content: .nickname) { return NSLocalizedString("moderation.hidden_name", comment: "") }
        if let o = p.nicknameOverrideID { return text(o) }
        return p.nickname
    }

    /// Engine of the room this device hosts (demo or real). nil on a player's phone.
    var hostEngine: GameEngine? { demo?.engine ?? host?.engine }

    var myID: PlayerID {
        mode == .demo ? "human" : profile.playerID
    }

    // MARK: Demo (offline)

    func startDemo(seed: UInt64 = UInt64.random(in: 1...UInt64.max >> 1),
                   config: GameConfig = DemoRoom.defaultConfig(),
                   botNames: [String] = []) {
        leave()
        let room = DemoRoom(seed: seed,
                            botCount: 3,
                            config: config,
                            library: content,
                            humanNickname: profile.effectiveNickname,
                            botNames: botNames)
        demo = room
        mode = .demo
        if let human = room.human {
            wire(human)
            publicState = human.publicState
            privateState = human.privateState
        }
        do {
            try room.startGame()
        } catch {
            failureText = "\(error)"
        }
    }

    // MARK: Hosting (real room)

    /// Opens a room. This is the first moment Multipeer objects are created (local-network prompt).
    func createRoom(config: GameConfig) {
        leave()
        let code = String(format: "%04d", Int.random(in: 0...9999))
        let engine = GameEngine(config: config,
                                roomCode: code,
                                library: content,
                                random: SystemRandom(),
                                implementedGames: DemoRoom.defaultImplemented)
        let transport = MultipeerTransport(role: .host(roomCode: code,
                                                       hostName: profile.effectiveNickname,
                                                       contentVersion: engine.contentVersion),
                                           displayName: "host-\(code)")
        transport.onFailure = { [weak self] text in self?.failureText = text }
        let session = HostSession(engine: engine, transport: transport, filter: textFilter)
        session.onStateChanged = { [weak self] in
            guard let self = self else { return }
            self.publicState = engine.publicState()
        }
        host = session
        hostTransport = transport
        mode = .host
        session.start()
        publicState = engine.publicState()
    }

    func updateConfig(_ config: GameConfig) {
        guard let host = host else { return }
        do {
            try host.engine.updateConfig(config)
            host.broadcastState()
        } catch {
            failureText = "\(error)"
        }
    }

    func startRoomGame() {
        guard let host = host else { return }
        do {
            try host.engine.start()
            hostTransport?.stopAdvertising()
            host.broadcastState()
        } catch {
            failureText = "\(error)"
        }
    }

    func kick(_ id: PlayerID) {
        host?.kick(id)   // broadcasts, which refreshes publicState through onStateChanged
    }

    // MARK: Host "continue" (demo and real)

    /// True when the engine can leave the current phase right now.
    var canContinue: Bool {
        guard let e = hostEngine else { return false }
        return !e.isWaitingForScores || e.pendingScorePlayers.isEmpty
    }

    func hostContinue() {
        switch mode {
        case .demo:
            demo?.advance(autoHuman: false)
        case .host:
            host?.step()
        default:
            break
        }
    }

    // MARK: Joining (player phone)

    /// Starts looking for rooms. First moment Multipeer objects exist on a player's device.
    func beginBrowsing() {
        leave()
        let transport = MultipeerTransport(role: .client, displayName: profile.playerID)
        let player = Player(id: profile.playerID, nickname: profile.effectiveNickname, avatar: profile.avatar)
        let session = ClientSession(player: player,
                                    transport: transport,
                                    contentVersion: content?.releaseVersion ?? 0,
                                    uiLocale: Locale.preferredLanguages.first ?? "en")
        transport.onRoomsChanged = { [weak self] list in self?.rooms = list }
        transport.onFailure = { [weak self] text in self?.failureText = text }
        wire(session)
        client = session
        clientTransport = transport
        mode = .client
        session.start()
    }

    func join(_ room: DiscoveredRoom) {
        rejectReason = nil
        // Friendly early check; the host's handshake remains the authority.
        if room.protocolVersion != WireProtocol.version {
            rejectReason = .protocolMismatch
            return
        }
        if room.contentVersion != (content?.releaseVersion ?? 0) {
            rejectReason = .contentPackMismatch
            return
        }
        clientTransport?.connect(to: room)
    }

    // MARK: Player inputs

    func send(_ input: InputPayload) {
        switch mode {
        case .demo: demo?.human?.send(input)
        case .client: client?.send(input)
        default: break
        }
    }

    func report(_ target: PlayerID, _ what: ReportedContent) {
        send(.report(target: target, content: what))
    }

    // MARK: Leaving

    func leave() {
        demo = nil
        host?.stop()
        host = nil
        hostTransport = nil
        client?.stop()
        client = nil
        clientTransport = nil
        mode = .none
        publicState = nil
        privateState = nil
        rooms = []
        connectedToRoom = false
        rejectReason = nil
        connectionEnded = false
        hideList.wipe()
    }

    // MARK: Private

    private func wire(_ session: ClientSession) {
        session.onPublicState = { [weak self] s in
            self?.publicState = s
            self?.connectedToRoom = true
            if s.phase == .ended { self?.hideList.wipe() }
        }
        session.onPrivateState = { [weak self] s in self?.privateState = s }
        session.onReject = { [weak self] r in self?.rejectReason = r }
        session.onEnd = { [weak self] in self?.connectionEnded = true }
    }
}

#if DEBUG
// MARK: - Screenshot mode (Debug builds only)
//
// Launch arguments, used by .github/workflows/screenshots.yml to take App Store screenshots of the
// REAL app running in the simulator:
//   -screenshot home|motion|sabotage|award|bet|results|tv
// The demo is advanced with a fixed seed until the requested screen is reached. Nothing here exists
// in Release builds.
extension AppModel {
    static var screenshotScene: String? {
        UserDefaults.standard.string(forKey: "screenshot")
    }

    func runScreenshotScene(_ scene: String) {
        guard scene != "home" else { return }
        let english = (Locale.preferredLanguages.first ?? "").hasPrefix("en")
        var config = DemoRoom.defaultConfig()
        config.contentLocale = english ? .en : .zhHans
        let names = english ? ["Mia", "Leo", "Sam"] : ["小鱼", "阿杰", "豆豆"]
        startDemo(seed: 7, config: config, botNames: names)
        guard let room = demo else { return }

        func reached() -> Bool {
            let e = room.engine
            switch scene {
            case "motion":
                return e.phase == .miniGame && e.currentMiniGame?.kind == .motion
                    && e.pendingScorePlayers.contains("human")
            case "sabotage":
                guard e.phase == .sabotage, let p = privateState, let pub = publicState else { return false }
                return p.vouchers.contains { pub.round >= $0.usableFromRound && pub.round <= $0.lastUsableRound }
            case "award": return e.phase == .award
            case "bet": return e.phase == .betting
            case "results": return e.phase == .ended
            case "tv": return e.phase == .tile && (publicState?.events.count ?? 0) >= 2
            default: return true
            }
        }
        var steps = 0
        while !reached() && room.engine.phase != .ended && steps < 3000 {
            room.advance(autoHuman: true)
            steps += 1
        }
    }
}
#endif

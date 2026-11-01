import Foundation
import FrenemyCore

/// 单机演示: one host engine, scripted bots and (optionally) one human, all connected through the
/// in-memory LoopbackHub. Completely offline: no Multipeer, no Bonjour, no local-network prompt.
public final class DemoRoom {
    public static let defaultImplemented: Set<MiniGameID> = [.shakeFest, .drawChain, .melonSlash, .statue]

    public let engine: GameEngine
    public let host: HostSession
    public let human: ClientSession?
    public private(set) var bots: [BotClient] = []
    /// Phase/round transitions seen during the run, e.g. "r3:miniGame".
    public private(set) var log: [String] = []

    private let hub = LoopbackHub()
    private let random: RandomSource
    private let humanRandom: RandomSource

    public init(seed: UInt64 = 2026,
                botCount: Int = 3,
                config: GameConfig = DemoRoom.defaultConfig(),
                library: ContentLibrary? = nil,
                humanNickname: String? = "You") {
        precondition(botCount >= 1 && botCount + (humanNickname == nil ? 0 : 1) <= Limits.maxPlayers)
        random = SeededRandom(seed: seed)
        humanRandom = SeededRandom(seed: seed &+ 99)
        engine = GameEngine(config: config,
                            roomCode: "DEMO",
                            library: library,
                            random: SeededRandom(seed: seed &+ 1),
                            implementedGames: DemoRoom.defaultImplemented)
        host = HostSession(engine: engine, transport: hub.makeHostTransport())
        host.start()

        let version = library?.releaseVersion ?? 0
        var humanSession: ClientSession?
        if let name = humanNickname {
            let p = Player(id: "human", nickname: name, avatar: Avatar(shape: 0, face: 0, colorIndex: 0))
            let s = ClientSession(player: p, transport: hub.makeClientTransport(id: p.id), contentVersion: version, uiLocale: "zh-Hans")
            humanSession = s
        }
        human = humanSession
        for i in 1...botCount {
            let p = Player(id: "bot\(i)", nickname: "Bot\(i)", avatar: Avatar(shape: i % 4, face: i % 3, colorIndex: i % 6))
            let s = ClientSession(player: p, transport: hub.makeClientTransport(id: p.id), contentVersion: version, uiLocale: "zh-Hans")
            bots.append(BotClient(session: s, random: random))
        }
        human?.start()
        for b in bots { b.session.start() }
    }

    public static func defaultConfig() -> GameConfig {
        var c = GameConfig()
        c.totalRounds = 8
        c.mode = .island
        return c
    }

    /// Starts the game (needs at least 2 joined players).
    public func startGame() throws {
        try engine.start()
        host.broadcastState()
        record()
    }

    /// One host "continue". If the engine waits for the human's score, the human is auto-played so
    /// the demo can run unattended. Returns false when nothing more can happen.
    @discardableResult
    public func advance(autoHuman: Bool = true) -> Bool {
        if engine.phase == .ended { return false }
        if autoHuman, let h = human, engine.pendingScorePlayers.contains(h.player.id) {
            h.send(.score(humanRandom.nextInt(upperBound: 100)))
        }
        confirmMissionIfScheduled()
        let moved = host.step()
        record()
        return moved
    }

    /// Plays the whole game: the "自动走一轮核心流程" the review notes refer to.
    public func autoplay(maxSteps: Int = 5000) -> PublicState {
        var steps = 0
        while engine.phase != .ended && steps < maxSteps {
            if !advance() { break }
            steps += 1
        }
        return engine.publicState()
    }

    private func confirmMissionIfScheduled() {
        if engine.phase == .miniGame, engine.missionRounds.contains(engine.round) {
            engine.confirmMissionCompleted(round: engine.round)
        }
    }

    private func record() {
        log.append("r\(engine.round):\(engine.phase.rawValue)")
    }
}

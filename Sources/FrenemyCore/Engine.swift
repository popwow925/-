import Foundation

public enum EngineError: Error, Equatable, Sendable {
    case wrongPhase
    case notEnoughPlayers
    case invalidConfig
    case unsupportedContentLocale
    case noMiniGamesAvailable
}

/// Host-authoritative game state machine. Transport-agnostic and UI-agnostic:
/// it only mutates state; HostSession (transport glue) and the UI read PublicState / PrivateState.
///
/// `step()` is the single "continue" action (host button or timer). Phases that wait for player
/// input (mini-games, finale) refuse to advance until everyone connected has submitted.
public final class GameEngine {
    // Configuration
    public internal(set) var config: GameConfig
    public let roomCode: String
    let library: ContentLibrary?
    let random: RandomSource
    let implementedGames: Set<MiniGameID>

    // Players
    var players: [Player] = []
    var positions: [PlayerID: Int] = [:]
    var shownTitles: [PlayerID: [String]] = [:]
    var nicknameOverride: [PlayerID: String] = [:]
    var kicked: Set<PlayerID> = []

    // Flow
    public internal(set) var phase: GamePhase = .lobby
    public internal(set) var round: Int = 0
    var plan: [MiniGameID] = []
    var stateVersion = 0
    var events: [PublicEvent] = []

    // Round scratch
    var rolls: [PlayerID: Int] = [:]
    var reversedNext: Set<PlayerID> = []
    var skipShopNext: Set<PlayerID> = []
    var shopOpenFor: Set<PlayerID> = []
    var scores: [PlayerID: Int] = [:]
    var lastResults: [RankedResult] = []
    var finaleWinners: Set<PlayerID> = []
    /// Temporary rules (content IDs) that apply to a player in the next mini-game, e.g. "rule.left_hand".
    var activeRules: [PlayerID: String] = [:]

    // Subsystems
    var ledger: SabotageLedger?
    var missions: [MissionAssignment] = []
    var missionDone: Set<Int> = []
    var award: AwardRound?
    var bets: FinalBetBook?
    public internal(set) var reports = ReportInbox()
    public internal(set) var standings: [RankedResult]?
    var revealedMissions: [MissionAssignment]?

    public static let funNicknameIDs: [String] = (1...12).map { String(format: "nickname.fun.%03d", $0) }

    public init(config: GameConfig = GameConfig(),
                roomCode: String,
                library: ContentLibrary?,
                random: RandomSource,
                implementedGames: Set<MiniGameID>) {
        self.config = config
        self.roomCode = roomCode
        self.library = library
        self.random = random
        self.implementedGames = implementedGames
    }

    // MARK: - Read-only access

    public var contentVersion: Int { library?.releaseVersion ?? 0 }
    public var playerIDs: [PlayerID] { players.map { $0.id } }
    public var connectedIDs: [PlayerID] { players.filter { $0.isConnected }.map { $0.id } }
    public var totalRounds: Int { config.totalRounds }
    public var currentMiniGame: MiniGameID? {
        guard round >= 1, round <= plan.count else { return nil }
        return plan[round - 1]
    }
    public var sabotageStats: SabotageLedger.Stats? { ledger?.stats }

    // MARK: - Lobby

    /// Adds a player after the handshake. Reconnection of an existing, disconnected player is allowed in any phase.
    public func join(_ player: Player, protocolVersion: Int, contentVersion clientContent: Int) -> RejectReason? {
        if kicked.contains(player.id) { return .kicked }
        if let reason = Handshake.validate(clientProtocol: protocolVersion,
                                           clientContentVersion: clientContent,
                                           hostContentVersion: contentVersion) {
            return reason
        }
        if let i = players.firstIndex(where: { $0.id == player.id }) {
            players[i].isConnected = true      // reconnect
            bump()
            return nil
        }
        guard phase == .lobby else { return .gameInProgress }
        guard players.count < Limits.maxPlayers else { return .roomFull }
        players.append(player)
        positions[player.id] = 0
        bump()
        return nil
    }

    public func markConnected(_ id: PlayerID, _ connected: Bool) {
        guard let i = players.firstIndex(where: { $0.id == id }) else { return }
        players[i].isConnected = connected
        bump()
    }

    public func leaveLobby(_ id: PlayerID) {
        guard phase == .lobby else { markConnected(id, false); return }
        players.removeAll { $0.id == id }
        positions[id] = nil
        bump()
    }

    public func updateConfig(_ newConfig: GameConfig) throws {
        guard phase == .lobby else { throw EngineError.wrongPhase }
        guard newConfig.isValid else { throw EngineError.invalidConfig }
        if let lib = library, !lib.supports(newConfig.contentLocale) { throw EngineError.unsupportedContentLocale }
        config = newConfig
        bump()
    }

    // MARK: - Start

    public func start() throws {
        guard phase == .lobby else { throw EngineError.wrongPhase }
        guard players.count >= Limits.minPlayers else { throw EngineError.notEnoughPlayers }
        guard config.isValid else { throw EngineError.invalidConfig }
        if let lib = library, !lib.supports(config.contentLocale) { throw EngineError.unsupportedContentLocale }

        let regular = MiniGamePool.regular(implemented: implementedGames)
        let finale = MiniGamePool.finale(implemented: implementedGames)
        guard !regular.isEmpty, !finale.isEmpty else { throw EngineError.noMiniGamesAvailable }

        var out: [MiniGameID] = []
        for _ in 1..<config.totalRounds {
            let candidates = regular.filter { $0 != out.last }
            let pool = candidates.isEmpty ? regular : candidates
            out.append(pool[random.nextInt(upperBound: pool.count)])
        }
        out.append(finale[random.nextInt(upperBound: finale.count)])
        plan = out

        ledger = SabotageLedger(players: playerIDs, strongEnabled: config.strongSabotageEnabled)
        missions = MissionRules.plan(totalRounds: config.totalRounds,
                                     miniGames: plan,
                                     players: playerIDs,
                                     enabled: config.mode == .island && config.secretMissionsEnabled,
                                     friendlyMode: !config.strongSabotageEnabled,
                                     random: random)
        round = 1
        phase = .roundStart
        events = [PublicEvent(key: "game.start")]
        bump()
    }

    // MARK: - Flow

    /// Advances one phase if allowed. Returns false when the engine is waiting for input.
    @discardableResult
    public func step() -> Bool {
        defer { bump() }
        switch phase {
        case .lobby, .ended:
            return false

        case .roundStart:
            events = [PublicEvent(key: "round.start", params: ["round": String(round)])]
            if round == config.totalRounds {
                bets = FinalBetBook(players: playerIDs)
                phase = .betting
            } else if config.mode == .island {
                phase = .roll
            } else {
                beginMiniGame()
            }
            return true

        case .roll:
            events = []
            for p in players where p.isConnected {
                let v = Board.rollDie(using: random)
                rolls[p.id] = v
                events.append(PublicEvent(key: "roll.result", params: ["player": p.id, "value": String(v)]))
            }
            phase = .move
            return true

        case .move:
            events = []
            for p in players {
                guard let steps = rolls[p.id] else { continue }
                let from = positions[p.id] ?? 0
                positions[p.id] = Board.advance(from: from, steps: steps, reversed: reversedNext.contains(p.id))
            }
            rolls.removeAll()
            reversedNext.removeAll()
            phase = .tile
            applyTileOutcomes()
            return true

        case .tile:
            shopOpenFor.removeAll()
            beginMiniGame()
            return true

        case .miniGame, .finale:
            guard connectedIDs.allSatisfy({ scores[$0] != nil }) else { return false }
            resolveMiniGame()
            return true

        case .payout:
            doPayout()
            return true

        case .sabotage:
            ledger?.endRound(round)
            if AwardRules.isAwardRound(round, totalRounds: config.totalRounds) {
                startAward()
            } else {
                nextRound()
            }
            return true

        case .award:
            if var a = award, !a.isFinalized {
                let winners = a.finalize()
                award = a
                events = winners.map { PublicEvent(key: "award.winner", params: ["player": $0.player, "title": a.titleID]) }
                return true
            }
            commitAward()
            nextRound()
            return true

        case .betting:
            beginFinale()
            return true

        case .results:
            phase = .ended
            reports.wipe()
            revealedMissions = missions
            nicknameOverride.removeAll()
            return true
        }
    }

    // MARK: - Internals used by step()

    func bump() { stateVersion += 1 }

    func setCrystals(_ id: PlayerID, _ value: Int) {
        guard let i = players.firstIndex(where: { $0.id == id }) else { return }
        players[i].crystals = max(0, value)
    }

    func crystals(_ id: PlayerID) -> Int {
        players.first(where: { $0.id == id })?.crystals ?? 0
    }

    func nextRound() {
        round += 1
        phase = .roundStart
    }

    func beginMiniGame() {
        scores.removeAll()
        phase = .miniGame
    }

    func beginFinale() {
        scores.removeAll()
        phase = .finale
    }

    func resolveMiniGame() {
        let lowerIsBetter = currentMiniGame?.lowerIsBetter ?? false
        var effective: [PlayerID: Int] = [:]
        for p in players {
            if let s = scores[p.id] {
                effective[p.id] = lowerIsBetter ? -s : s
            } else {
                effective[p.id] = Int.min / 2      // disconnected / no result -> last
            }
        }
        lastResults = Ranking.compete(scores: effective)
        if phase == .finale { finaleWinners = Set(Ranking.champions(lastResults)) }
        activeRules.removeAll()
        phase = .payout
    }
}

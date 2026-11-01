import Foundation

// Two separate view-model families. This split is what keeps private data out of the host's UI layer:
//   PublicState  -> TV display, host "公共控制台" (mirror mode), every player's public area
//   PrivateState -> sent to exactly ONE player's device, never rendered on TV or on the host console
// Nothing secret (undercover words, mission text, votes, vouchers, bets) may ever be added to PublicState.

public enum GamePhase: String, Codable, Sendable {
    case lobby
    case roundStart
    case roll          // everyone shakes/taps to roll (体感阶段)
    case move
    case tile
    case miniGame
    case payout
    case sabotage
    case award
    case betting       // last round only, before the finale mini-game
    case finale
    case results
    case ended
}

public struct PublicEvent: Codable, Equatable, Sendable {
    /// Content ID, rendered on each device from the room's content pack.
    public let key: String
    public let params: [String: String]
    public init(key: String, params: [String: String] = [:]) {
        self.key = key
        self.params = params
    }
}

public struct PublicPlayer: Codable, Equatable, Identifiable, Sendable {
    public let id: PlayerID
    public var nickname: String
    public var avatar: Avatar
    public var crystals: Int
    public var shields: Int
    public var position: Int
    public var isConnected: Bool
    /// Titles the player has CONFIRMED to show (ids only).
    public var shownTitleIDs: [String]
    /// Built-in fun nickname (content ID) applied by a 换外号 card; nil = real nickname.
    public var nicknameOverrideID: String?
}

public struct PublicState: Codable, Equatable, Sendable {
    public var stateVersion: Int
    public var roomCode: String
    public var phase: GamePhase
    public var mode: GameMode
    public var round: Int
    public var totalRounds: Int
    public var contentLocale: ContentLocale
    public var strongSabotageEnabled: Bool
    public var players: [PublicPlayer]
    public var miniGame: MiniGameID?
    public var events: [PublicEvent]
    /// Temporary rules (content IDs) affecting a player in the next mini-game, e.g. "rule.left_hand".
    public var activeRules: [PlayerID: String]
    /// Final standings, only in .results/.ended.
    public var standings: [RankedResult]?
    /// Secret missions, revealed publicly ONLY after the game ends.
    public var revealedMissions: [MissionAssignment]?
    public var isLastRound: Bool { round == totalRounds }
}

// MARK: - Private, per player

public struct VoucherView: Codable, Equatable, Sendable {
    public let id: Int
    public let issuedRound: Int
    public var usableFromRound: Int { issuedRound + 1 }
    public var lastUsableRound: Int { issuedRound + 2 }
}

public enum UndercoverSide: String, Codable, Sendable { case civilian, undercover }

public struct PrivateWord: Codable, Equatable, Sendable {
    public let pairID: String
    public let side: UndercoverSide
}

public struct PrivateMission: Codable, Equatable, Sendable {
    public let round: Int
    public let missionID: String
}

public struct PrivateAwardPrompt: Codable, Equatable, Sendable {
    public let titleID: String
}

public struct PrivateState: Codable, Equatable, Sendable {
    public var stateVersion: Int
    public var player: PlayerID
    public var vouchers: [VoucherView]
    public var mission: PrivateMission?
    public var word: PrivateWord?
    public var awardPrompt: PrivateAwardPrompt?
    public var bet: Bet?
}

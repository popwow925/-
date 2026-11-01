import Foundation

// MARK: - Cards

public enum CardTier: String, Codable, Sendable { case light, strong }

public enum CardKind: String, Codable, CaseIterable, Sendable {
    // 轻互动 (default on)
    case reverseDice
    case leftHandRule
    case swapNickname      // 换成内置搞笑外号（只从内置库取）
    // 强互动 (default off)
    case stealCrystals
    case skipShop

    public var tier: CardTier {
        switch self {
        case .reverseDice, .leftHandRule, .swapNickname: return .light
        case .stealCrystals, .skipShop: return .strong
        }
    }
}

// MARK: - Results used for issuing vouchers

public struct RankedResult: Codable, Equatable, Sendable {
    public let player: PlayerID
    /// Competition ranking: 1 is best; ties share a rank (1,2,2,4).
    public let rank: Int
    public init(player: PlayerID, rank: Int) {
        self.player = player
        self.rank = rank
    }
}

public enum SabotageRules {
    /// ⌈n / 3⌉, at least 1.
    public static func voucherCount(playerCount: Int) -> Int {
        max(1, (playerCount + 2) / 3)
    }

    /// Vouchers are issued after rounds 1 ... totalRounds-2 (the last two rounds issue none).
    public static func issuesVouchers(afterRound round: Int, totalRounds: Int) -> Bool {
        round >= 1 && round <= totalRounds - 2
    }

    /// Players who receive a voucher after this round's mini-game.
    /// Worst ⌈n/3⌉ players; anyone tied with the last included player is included too.
    public static func voucherRecipients(results: [RankedResult], round: Int, totalRounds: Int) -> [PlayerID] {
        guard issuesVouchers(afterRound: round, totalRounds: totalRounds), !results.isEmpty else { return [] }
        let k = voucherCount(playerCount: results.count)
        let worstFirst = results.sorted { a, b in
            a.rank != b.rank ? a.rank > b.rank : a.player < b.player
        }
        let cut = worstFirst[min(k, worstFirst.count) - 1].rank
        return worstFirst.filter { $0.rank >= cut }.map { $0.player }
    }

    /// Everyone tied for last place this round (for the "落后奖励" shield).
    public static func lastPlace(results: [RankedResult]) -> [PlayerID] {
        guard let worst = results.map({ $0.rank }).max() else { return [] }
        return results.filter { $0.rank == worst }.map { $0.player }.sorted()
    }
}

// MARK: - Voucher

public struct Voucher: Codable, Equatable, Identifiable, Sendable {
    public let id: Int
    public let owner: PlayerID
    public let issuedRound: Int

    public init(id: Int, owner: PlayerID, issuedRound: Int) {
        self.id = id
        self.owner = owner
        self.issuedRound = issuedRound
    }

    /// Issued in round N: usable in N+1 and N+2, never in the last round.
    public func usability(inRound round: Int, totalRounds: Int) -> VoucherUsability {
        if round == totalRounds { return .lastRoundDisabled }
        if round <= issuedRound { return .notYet }
        if round > issuedRound + 2 { return .expired }
        return .usable
    }
}

public enum VoucherUsability: Sendable { case usable, notYet, expired, lastRoundDisabled }

// MARK: - Play results

public enum PlayRejection: String, Codable, Sendable {
    case noVoucher
    case notYetUsable
    case expired
    case lastRoundDisabled
    case alreadyUsedThisRound
    case selfTarget
    case strongDisabled
    case targetAlreadyHitThisRound
    case unknownPlayer
    case wrongPhase
}

public enum PlayResult: Equatable, Sendable {
    case applied
    case blockedByShield
    case rejected(PlayRejection)
}

// MARK: - Ledger

/// Pure value type holding vouchers, shields and neutral stats for one game.
/// The engine owns one instance; nothing here knows about networking or UI.
public struct SabotageLedger: Codable, Equatable, Sendable {
    public private(set) var vouchers: [Voucher] = []
    public private(set) var shields: [PlayerID: Int] = [:]
    public private(set) var usesByCard: [CardKind: Int] = [:]
    public private(set) var shieldUses: Int = 0

    private var nextVoucherID = 1
    private var usedThisRound: Set<PlayerID> = []
    private var strongTargetsThisRound: Set<PlayerID> = []
    private var knownPlayers: Set<PlayerID> = []

    public init(players: [PlayerID], strongEnabled: Bool) {
        knownPlayers = Set(players)
        for p in players { shields[p] = strongEnabled ? 1 : 0 }   // 强互动开启时每人初始 1 个
    }

    // MARK: Issuing

    public mutating func issueVouchers(results: [RankedResult], round: Int, totalRounds: Int) {
        for p in SabotageRules.voucherRecipients(results: results, round: round, totalRounds: totalRounds) {
            vouchers.append(Voucher(id: nextVoucherID, owner: p, issuedRound: round))
            nextVoucherID += 1
        }
    }

    // MARK: Shields

    public func shieldCount(_ p: PlayerID) -> Int { shields[p] ?? 0 }

    /// Adds one shield, respecting the cap. Returns false if already at cap.
    @discardableResult
    public mutating func addShield(_ p: PlayerID) -> Bool {
        guard knownPlayers.contains(p) else { return false }
        let n = shields[p] ?? 0
        guard n < Limits.maxShields else { return false }
        shields[p] = n + 1
        return true
    }

    /// 落后奖励: every last-place player gets one shield (if below cap).
    public mutating func grantLastPlaceShields(results: [RankedResult]) {
        for p in SabotageRules.lastPlace(results: results) { addShield(p) }
    }

    // MARK: Playing

    public func vouchers(of p: PlayerID, round: Int, totalRounds: Int) -> [Voucher] {
        vouchers.filter { $0.owner == p && $0.usability(inRound: round, totalRounds: totalRounds) == .usable }
    }

    public mutating func play(card: CardKind,
                              by player: PlayerID,
                              on target: PlayerID,
                              round: Int,
                              totalRounds: Int,
                              strongEnabled: Bool) -> PlayResult {
        guard knownPlayers.contains(player), knownPlayers.contains(target) else { return .rejected(.unknownPlayer) }
        if round == totalRounds { return .rejected(.lastRoundDisabled) }
        if player == target { return .rejected(.selfTarget) }
        if card.tier == .strong && !strongEnabled { return .rejected(.strongDisabled) }
        if usedThisRound.contains(player) { return .rejected(.alreadyUsedThisRound) }
        if card.tier == .strong && strongTargetsThisRound.contains(target) { return .rejected(.targetAlreadyHitThisRound) }

        let mine = vouchers.filter { $0.owner == player }
        guard !mine.isEmpty else { return .rejected(.noVoucher) }
        let usable = mine.filter { $0.usability(inRound: round, totalRounds: totalRounds) == .usable }
        guard let chosen = usable.min(by: { $0.issuedRound < $1.issuedRound }) else {
            // Report the most informative reason.
            if mine.contains(where: { $0.usability(inRound: round, totalRounds: totalRounds) == .notYet }) {
                return .rejected(.notYetUsable)
            }
            return .rejected(.expired)
        }

        // Spend the voucher and the player's single use this round.
        vouchers.removeAll { $0.id == chosen.id }
        usedThisRound.insert(player)
        usesByCard[card, default: 0] += 1

        if card.tier == .strong {
            strongTargetsThisRound.insert(target)
            if (shields[target] ?? 0) > 0 {
                shields[target, default: 0] -= 1
                shieldUses += 1
                return .blockedByShield
            }
        }
        return .applied
    }

    // MARK: Round end

    /// Call once after each round is fully resolved.
    public mutating func endRound(_ round: Int) {
        usedThisRound.removeAll()
        strongTargetsThisRound.removeAll()
        // Issued in N, expires at the end of N+2.
        vouchers.removeAll { $0.issuedRound + 2 <= round }
    }

    // MARK: Neutral stats (no "被坑次数")

    public struct Stats: Equatable, Sendable {
        public var totalCardsPlayed: Int
        public var mostUsedCard: CardKind?
        public var shieldUses: Int
    }

    public var stats: Stats {
        let total = usesByCard.values.reduce(0, +)
        let most = usesByCard
            .sorted { a, b in a.value != b.value ? a.value > b.value : a.key.rawValue < b.key.rawValue }
            .first?.key
        return Stats(totalCardsPlayed: total, mostUsedCard: most, shieldUses: shieldUses)
    }
}

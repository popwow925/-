import Foundation

// 终局大赌: only in the LAST round, bets are placed BEFORE the final mini-game.
// Target = winner(s) of the final mini-game. Stake is frozen at placement.
// Correct: principal back + equal profit (1:1). Wrong: stake is voided (not transferred).

public struct Bet: Codable, Equatable, Sendable {
    public let bettor: PlayerID
    public let target: PlayerID
    public let stake: Int
    public init(bettor: PlayerID, target: PlayerID, stake: Int) {
        self.bettor = bettor
        self.target = target
        self.stake = stake
    }
}

public enum BetError: String, Codable, Error, Sendable {
    case selfBet
    case unknownTarget
    case stakeNotPositive
    case stakeAboveCap
    case alreadyBet
    case notEnoughCrystals
    case wrongPhase
}

public struct BetOutcome: Equatable, Sendable {
    public let bet: Bet
    public let won: Bool
    /// Crystals credited back to the bettor (2 × stake if won, else 0).
    public let credited: Int
}

public enum FinalBetRules {
    /// Maximum stake = crystals × ratio, floored. Ratio is 20% for rank 1 and 50% for the last
    /// rank, linear in between (tentative values; tune after playtests).
    /// Integer arithmetic only, so there are no floating point surprises.
    public static func maxStake(crystals: Int, rank: Int, playerCount n: Int) -> Int {
        guard crystals > 0 else { return 0 }
        let r = min(max(rank, 1), max(n, 1))
        if n <= 1 { return crystals * 200 / 1000 }
        // permille = 200 + 300 * (r-1)/(n-1)
        let numerator = crystals * (200 * (n - 1) + 300 * (r - 1))
        let denominator = 1000 * (n - 1)
        return numerator / denominator
    }
}

public struct FinalBetBook: Codable, Equatable, Sendable {
    public private(set) var bets: [PlayerID: Bet] = [:]
    private let players: Set<PlayerID>

    public init(players: [PlayerID]) {
        self.players = Set(players)
    }

    /// Places a bet and freezes the stake by deducting it from `crystals`.
    /// `ranks` are the standings BEFORE betting (competition ranks).
    public mutating func place(_ bet: Bet,
                               crystals: inout [PlayerID: Int],
                               ranks: [RankedResult]) -> Result<Void, BetError> {
        guard players.contains(bet.bettor), players.contains(bet.target) else { return .failure(.unknownTarget) }
        if bet.bettor == bet.target { return .failure(.selfBet) }
        if bet.stake <= 0 { return .failure(.stakeNotPositive) }
        if bets[bet.bettor] != nil { return .failure(.alreadyBet) }
        let have = crystals[bet.bettor] ?? 0
        if bet.stake > have { return .failure(.notEnoughCrystals) }
        let rank = ranks.first(where: { $0.player == bet.bettor })?.rank ?? ranks.count
        let cap = FinalBetRules.maxStake(crystals: have, rank: rank, playerCount: players.count)
        if bet.stake > cap { return .failure(.stakeAboveCap) }
        crystals[bet.bettor] = have - bet.stake
        bets[bet.bettor] = bet
        return .success(())
    }

    /// Settles after the final mini-game. `winners` = everyone tied for first; a bet on any of
    /// them counts as correct. Credits 2 × stake for correct bets; wrong stakes stay voided.
    public mutating func settle(winners: Set<PlayerID>, crystals: inout [PlayerID: Int]) -> [BetOutcome] {
        var outcomes: [BetOutcome] = []
        for bet in bets.values.sorted(by: { $0.bettor < $1.bettor }) {
            let won = winners.contains(bet.target)
            let credited = won ? bet.stake * 2 : 0
            if credited > 0 { crystals[bet.bettor, default: 0] += credited }
            outcomes.append(BetOutcome(bet: bet, won: won, credited: credited))
        }
        bets.removeAll()
        return outcomes
    }
}

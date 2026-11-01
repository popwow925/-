import Foundation

// 「谁最可能」颁奖 rules.
// - Held every 3 rounds (3, 6, 9 ...), never in the last round.
// - Ties: all tied players share the same title.
// - Winner may accept, decline (title stays vacant, NOT passed to runner-up) or replace
//   (only from the built-in healthy title library). Winner decides whether it appears in the report.
// - Raw votes are cleared once the round is finalized; reports never contain vote details.

public enum AwardRules {
    public static func awardRounds(totalRounds: Int) -> [Int] {
        guard totalRounds > 3 else { return [] }
        return (1...totalRounds).filter { $0 % 3 == 0 && $0 < totalRounds }
    }

    public static func isAwardRound(_ round: Int, totalRounds: Int) -> Bool {
        awardRounds(totalRounds: totalRounds).contains(round)
    }
}

/// Stable IDs of the built-in positive/neutral titles. Display text lives in Content/<locale>/titles.json.
public enum TitleLibrary {
    public static let ids: [String] = [
        "title.sunshine", "title.planner", "title.foodie", "title.comedian",
        "title.peacemaker", "title.explorer", "title.night_owl", "title.early_bird",
        "title.storyteller", "title.cheerleader", "title.lucky_star", "title.snack_hero",
    ]

    public static func contains(_ id: String) -> Bool { ids.contains(id) }
}

public enum AwardStatus: Codable, Equatable, Sendable {
    case pending
    case accepted
    case declined               // title vacant; never passed down
    case replaced(titleID: String)
}

public struct AwardWinner: Codable, Equatable, Sendable {
    public let player: PlayerID
    public var status: AwardStatus = .pending
    public var showInReport: Bool = true
    public init(player: PlayerID) { self.player = player }
}

public enum AwardError: Error, Equatable, Sendable {
    case selfVote
    case unknownPlayer
    case notAWinner
    case titleNotInLibrary
    case finalized
}

public struct AwardRound: Codable, Equatable, Sendable {
    public let questionID: String
    public let titleID: String
    public private(set) var winners: [AwardWinner] = []
    public private(set) var isFinalized = false

    private var votes: [PlayerID: PlayerID] = [:]   // voter -> target; cleared on finalize
    private let players: Set<PlayerID>

    public init(questionID: String, titleID: String, players: [PlayerID]) {
        self.questionID = questionID
        self.titleID = titleID
        self.players = Set(players)
    }

    public mutating func vote(voter: PlayerID, target: PlayerID) -> AwardError? {
        if isFinalized { return .finalized }
        guard players.contains(voter), players.contains(target) else { return .unknownPlayer }
        if voter == target { return .selfVote }
        votes[voter] = target          // last vote wins
        return nil
    }

    public var votesReceived: Int { votes.count }

    /// Tallies, stores winners (all tied top vote-getters) and wipes the raw votes.
    @discardableResult
    public mutating func finalize() -> [AwardWinner] {
        guard !isFinalized else { return winners }
        var counts: [PlayerID: Int] = [:]
        for t in votes.values { counts[t, default: 0] += 1 }
        if let top = counts.values.max() {
            winners = counts.filter { $0.value == top }.keys.sorted().map { AwardWinner(player: $0) }
        }
        votes.removeAll()
        isFinalized = true
        return winners
    }

    // MARK: Winner choices

    public mutating func accept(_ p: PlayerID) -> AwardError? { update(p) { $0.status = .accepted } }

    public mutating func decline(_ p: PlayerID) -> AwardError? {
        update(p) {
            $0.status = .declined
            $0.showInReport = false
        }
    }

    public mutating func replace(_ p: PlayerID, with newTitleID: String) -> AwardError? {
        guard TitleLibrary.contains(newTitleID) else { return .titleNotInLibrary }
        return update(p) { $0.status = .replaced(titleID: newTitleID) }
    }

    public mutating func setShowInReport(_ p: PlayerID, _ show: Bool) -> AwardError? {
        update(p) { w in
            if w.status != .declined { w.showInReport = show }
        }
    }

    private mutating func update(_ p: PlayerID, _ change: (inout AwardWinner) -> Void) -> AwardError? {
        guard let i = winners.firstIndex(where: { $0.player == p }) else { return .notAWinner }
        change(&winners[i])
        return nil
    }

    /// What the report may contain: only confirmed titles the winner chose to show. No vote data.
    public var reportEntries: [(player: PlayerID, titleID: String)] {
        winners.compactMap { w in
            guard w.showInReport else { return nil }
            switch w.status {
            case .accepted: return (w.player, titleID)
            case .replaced(let t): return (w.player, t)
            case .pending, .declined: return nil
            }
        }
    }
}

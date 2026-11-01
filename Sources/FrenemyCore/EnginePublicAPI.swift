import Foundation

// Small read-only accessors for modules outside FrenemyCore (App, FrenemyDemo).
extension GameEngine {
    /// Rounds in which a secret mission is scheduled. Holder identity is NOT exposed here.
    public var missionRounds: [Int] { missions.map { $0.round } }

    public var isWaitingForScores: Bool {
        (phase == .miniGame || phase == .finale) && !connectedIDs.allSatisfy { scores[$0] != nil }
    }

    public func hasSubmittedScore(_ player: PlayerID) -> Bool { scores[player] != nil }

    /// Players who still owe a score in the current mini-game.
    public var pendingScorePlayers: [PlayerID] {
        guard phase == .miniGame || phase == .finale else { return [] }
        return connectedIDs.filter { scores[$0] == nil }
    }
}

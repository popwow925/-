import Foundation

public enum Ranking {
    /// Competition ranking (1,2,2,4). Higher score is better.
    /// For "lower is better" games (e.g. 木头人 jitter), pass the negated score.
    public static func compete(scores: [PlayerID: Int]) -> [RankedResult] {
        let sorted = scores.sorted { a, b in
            a.value != b.value ? a.value > b.value : a.key < b.key
        }
        var out: [RankedResult] = []
        var lastScore: Int?
        var lastRank = 0
        for (index, entry) in sorted.enumerated() {
            let rank: Int
            if let ls = lastScore, ls == entry.value {
                rank = lastRank
            } else {
                rank = index + 1
                lastRank = rank
                lastScore = entry.value
            }
            out.append(RankedResult(player: entry.key, rank: rank))
        }
        return out
    }

    /// Everyone whose rank is 1 (ties share the title).
    public static func champions(_ results: [RankedResult]) -> [PlayerID] {
        results.filter { $0.rank == 1 }.map { $0.player }.sorted()
    }
}

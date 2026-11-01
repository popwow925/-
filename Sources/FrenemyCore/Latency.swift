import Foundation

// 抢答/反应公平性: minimal, honest implementation.
// - Handshake measures several RTTs; offset estimate = median(RTT) / 2 (one-way delay).
// - A client's reaction timestamp is corrected by that estimate and sorted by the host.
// - If two results differ by less than the threshold (default 50 ms, configurable), it is a tie.
// - We never claim precise timing.
//
// Clock note: callers supply the time source (closure). Do NOT use ProcessInfo.systemUptime or
// mach_absolute_time here without declaring the "system boot time" Required Reason API; the app
// layer passes a Date-based clock instead.

public struct LatencyEstimate: Equatable, Sendable {
    public let samples: Int
    /// Estimated one-way delay in milliseconds (median RTT / 2).
    public let oneWayMs: Int
}

public enum LatencyEstimator {
    public static func estimate(rttSamplesMs: [Int]) -> LatencyEstimate {
        let clean = rttSamplesMs.filter { $0 >= 0 }.sorted()
        guard !clean.isEmpty else { return LatencyEstimate(samples: 0, oneWayMs: 0) }
        let mid = clean.count / 2
        let median: Int = clean.count % 2 == 1 ? clean[mid] : (clean[mid - 1] + clean[mid]) / 2
        return LatencyEstimate(samples: clean.count, oneWayMs: median / 2)
    }
}

public struct ReactionSubmission: Equatable, Sendable {
    public let player: PlayerID
    /// Client-reported reaction time in ms measured against the client's own "go" moment.
    public let reactionMs: Int
    /// Estimated one-way delay for that player's link.
    public let oneWayMs: Int
    public init(player: PlayerID, reactionMs: Int, oneWayMs: Int) {
        self.player = player
        self.reactionMs = reactionMs
        self.oneWayMs = oneWayMs
    }
}

public enum ReactionJudge {
    /// The signal itself reaches each client after its own one-way delay, so a client's own
    /// measured reaction starts late by that amount. Corrected time = reported + delay.
    public static func correctedMs(_ s: ReactionSubmission) -> Int { s.reactionMs + s.oneWayMs }

    /// Returns players ordered fastest-first with competition ranks, treating results closer than
    /// `thresholdMs` to the CURRENT group leader as a tie. Early taps (negative) are excluded.
    public static func rank(_ submissions: [ReactionSubmission],
                            thresholdMs: Int = Limits.defaultTieThresholdMs) -> [RankedResult] {
        let valid = submissions.filter { $0.reactionMs >= 0 }
        let sorted = valid
            .map { ($0.player, correctedMs($0)) }
            .sorted { a, b in a.1 != b.1 ? a.1 < b.1 : a.0 < b.0 }

        var out: [RankedResult] = []
        var groupStart = 0          // time of the current tie-group leader
        var groupRank = 0
        for (i, entry) in sorted.enumerated() {
            if i == 0 || entry.1 - groupStart >= thresholdMs {
                groupRank = i + 1
                groupStart = entry.1
            }
            out.append(RankedResult(player: entry.0, rank: groupRank))
        }
        // Early/invalid taps share the last place after everyone valid.
        let lastRank = valid.count + 1
        for s in submissions where s.reactionMs < 0 {
            out.append(RankedResult(player: s.player, rank: lastRank))
        }
        return out
    }
}

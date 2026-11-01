import Foundation

// 内鬼任务 (island mode only).
// - 8 rounds: at most 1 mission per game; 12+ rounds: at most 2.
// - Never in a round whose mini-game is 谁是卧底 / 谁最可能, and never in the last round
//   (keeps 终局大赌 settlement free of overlapping bonuses).
// - Reward is credited at that round's crystal payout and shown as a "神秘奖励"; identity and
//   task text are revealed at the end of the game.
// - Disconnected holder: mission is void, no penalty.
// - Friendly mode uses light missions only. The pool is built-in and manually reviewed.

public struct MissionTemplate: Codable, Equatable, Sendable {
    public let id: String
    public let isLight: Bool
    public init(id: String, isLight: Bool) {
        self.id = id
        self.isLight = isLight
    }
}

public enum MissionPool {
    /// Display text lives in Content/<locale>/missions.json, keyed by id.
    public static let all: [MissionTemplate] = [
        MissionTemplate(id: "mission.make_table_laugh", isLight: true),
        MissionTemplate(id: "mission.finish_in_middle", isLight: true),
        MissionTemplate(id: "mission.cheer_last_place", isLight: true),
        MissionTemplate(id: "mission.stay_calm_all_round", isLight: true),
        MissionTemplate(id: "mission.pick_same_as_leader", isLight: false),
        MissionTemplate(id: "mission.dont_be_first", isLight: false),
    ]

    public static func available(friendlyMode: Bool) -> [MissionTemplate] {
        friendlyMode ? all.filter { $0.isLight } : all
    }
}

public enum MissionOutcome: String, Codable, Sendable {
    case pending, completed, failed, voided
}

public struct MissionAssignment: Codable, Equatable, Sendable {
    public let round: Int
    public let holder: PlayerID
    public let missionID: String
    public var outcome: MissionOutcome = .pending
    public init(round: Int, holder: PlayerID, missionID: String) {
        self.round = round
        self.holder = holder
        self.missionID = missionID
    }
}

public enum MissionRules {
    public static let rewardCrystals = 3

    public static func maxMissions(totalRounds: Int) -> Int {
        totalRounds >= 12 ? 2 : 1
    }

    /// A round may host a mission when its mini-game does not use private info and it is not the last round.
    public static func isEligible(round: Int, totalRounds: Int, miniGame: MiniGameID) -> Bool {
        round >= 1 && round < totalRounds && !miniGame.usesPrivateInfo
    }

    /// Picks mission rounds and holders. `miniGames[r-1]` is the mini-game of round r.
    /// Missions are spread out: at least 3 rounds apart.
    public static func plan(totalRounds: Int,
                            miniGames: [MiniGameID],
                            players: [PlayerID],
                            enabled: Bool,
                            friendlyMode: Bool,
                            random: RandomSource) -> [MissionAssignment] {
        guard enabled, players.count >= Limits.minPlayers, miniGames.count >= totalRounds else { return [] }
        let pool = MissionPool.available(friendlyMode: friendlyMode)
        guard !pool.isEmpty else { return [] }

        let eligible = (1..<totalRounds).filter {
            isEligible(round: $0, totalRounds: totalRounds, miniGame: miniGames[$0 - 1])
        }
        var chosenRounds: [Int] = []
        for r in eligible.shuffled(using: random) {
            if chosenRounds.count >= maxMissions(totalRounds: totalRounds) { break }
            if chosenRounds.allSatisfy({ abs($0 - r) >= 3 }) { chosenRounds.append(r) }
        }

        var usedMissions: Set<String> = []
        var assignments: [MissionAssignment] = []
        for r in chosenRounds.sorted() {
            let remaining = pool.filter { !usedMissions.contains($0.id) }
            let source = remaining.isEmpty ? pool : remaining
            let mission = source[random.nextInt(upperBound: source.count)]
            usedMissions.insert(mission.id)
            let holder = players[random.nextInt(upperBound: players.count)]
            assignments.append(MissionAssignment(round: r, holder: holder, missionID: mission.id))
        }
        return assignments
    }

    /// Resolves a mission at the round's payout. A disconnected holder voids it with no penalty.
    public static func resolve(_ assignment: MissionAssignment,
                               completed: Bool,
                               holderConnected: Bool) -> (assignment: MissionAssignment, reward: Int) {
        var a = assignment
        if !holderConnected {
            a.outcome = .voided
            return (a, 0)
        }
        a.outcome = completed ? .completed : .failed
        return (a, completed ? rewardCrystals : 0)
    }
}

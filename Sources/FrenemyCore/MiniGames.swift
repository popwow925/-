import Foundation

public enum MiniGameID: String, Codable, CaseIterable, Sendable {
    // P0
    case shakeFest        // 摇摇乐   (体感)
    case drawChain        // 画画传话 (触屏)
    case melonSlash       // 劈西瓜   (体感)
    case statue           // 木头人   (体感)
    // P1
    case quiz             // 知识抢答
    case quickTap         // 抢点反应
    case undercover       // 谁是卧底
    case mostLikely       // 谁最可能
    // P2 (after launch)
    case drawGuess        // 你画我猜
    case sync             // 默契问答

    public enum Kind: String, Codable, Sendable { case motion, touch, social }

    public var kind: Kind {
        switch self {
        case .shakeFest, .melonSlash, .statue: return .motion
        case .drawChain, .quiz, .quickTap, .drawGuess: return .touch
        case .undercover, .mostLikely, .sync: return .social
        }
    }

    /// Needs private information on phones (secret words, anonymous votes).
    public var usesPrivateInfo: Bool {
        self == .undercover || self == .mostLikely
    }

    /// Shipped in the first release (P0 + P1).
    public var isLaunchTitle: Bool {
        switch self {
        case .drawGuess, .sync: return false
        default: return true
        }
    }

    /// "Pure skill" games allowed as the 终局小游戏: no private info, short, fair on every device.
    public var allowedAsFinale: Bool {
        switch self {
        case .shakeFest, .melonSlash, .statue, .quickTap: return true
        default: return false
        }
    }

    /// Lower score is better (e.g. 木头人 jitter).
    public var lowerIsBetter: Bool { self == .statue }
}

public enum MiniGamePool {
    /// Games available for ordinary rounds. P0-only games are the ones implemented in the current milestone.
    public static func regular(implemented: Set<MiniGameID>) -> [MiniGameID] {
        MiniGameID.allCases.filter { $0.isLaunchTitle && implemented.contains($0) }
    }

    public static func finale(implemented: Set<MiniGameID>) -> [MiniGameID] {
        MiniGameID.allCases.filter { $0.allowedAsFinale && implemented.contains($0) }
    }
}

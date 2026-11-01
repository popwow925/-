import Foundation

public typealias PlayerID = String

public enum ContentLocale: String, Codable, CaseIterable, Sendable {
    case zhHans = "zh-Hans"
    case en = "en"
}

public enum GameMode: String, Codable, Sendable {
    case island   // 环岛大冒险
    case rush     // 小游戏连战
}

/// Room-level settings chosen by the host. Defaults follow the "友好优先" principle.
public struct GameConfig: Codable, Equatable, Sendable {
    public var mode: GameMode = .island
    public var totalRounds: Int = 8                 // 8 / 12 / 16
    public var contentLocale: ContentLocale = .zhHans
    public var strongSabotageEnabled: Bool = false  // 强互动默认关闭
    public var secretMissionsEnabled: Bool = true   // 内鬼任务默认开启，房主可关
    public var freeTextNicknamesEnabled: Bool = false
    public var customQuestionsEnabled: Bool = false

    public init() {}

    /// Allowed round counts per the plan.
    public static let allowedRounds: [Int] = [8, 12, 16]

    public var isValid: Bool {
        GameConfig.allowedRounds.contains(totalRounds)
    }
}

public struct Player: Codable, Equatable, Identifiable, Sendable {
    public let id: PlayerID
    public var nickname: String
    public var avatar: Avatar
    public var crystals: Int = 0
    public var shields: Int = 0
    public var isConnected: Bool = true

    public init(id: PlayerID, nickname: String, avatar: Avatar = Avatar()) {
        self.id = id
        self.nickname = nickname
        self.avatar = avatar
    }
}

/// Original, permission-free avatar: shape + expression + color index.
public struct Avatar: Codable, Equatable, Sendable {
    public var shape: Int = 0
    public var face: Int = 0
    public var colorIndex: Int = 0
    public init(shape: Int = 0, face: Int = 0, colorIndex: Int = 0) {
        self.shape = shape
        self.face = face
        self.colorIndex = colorIndex
    }
}

public enum Limits {
    public static let maxPlayers = 8
    public static let minPlayers = 2
    public static let maxShields = 2
    public static let reconnectWindowSeconds = 30
    public static let defaultTieThresholdMs = 50
}

import Foundation

// Content is keyed by stable IDs. The wire protocol only carries IDs; every device renders text
// from the pack of the ROOM's content locale (the host's choice). All locale packs in one app
// build share the same `version` (the content release number), which is what the handshake checks.

public struct QuizItem: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let question: String
    public let choices: [String]
    public let answerIndex: Int
}

public struct WordPair: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let civilian: String
    public let undercover: String
}

public struct ContentPack: Codable, Equatable, Sendable {
    public let version: Int
    public let locale: String
    /// id -> display text (titles, missions, cards, built-in nicknames, "谁最可能" prompts, event texts ...)
    public let entries: [String: String]
    public let quiz: [QuizItem]
    public let wordPairs: [WordPair]

    public init(version: Int, locale: String, entries: [String: String], quiz: [QuizItem] = [], wordPairs: [WordPair] = []) {
        self.version = version
        self.locale = locale
        self.entries = entries
        self.quiz = quiz
        self.wordPairs = wordPairs
    }

    public static func decode(_ data: Data) throws -> ContentPack {
        try JSONDecoder().decode(ContentPack.self, from: data)
    }

    public func ids(withPrefix prefix: String) -> [String] {
        entries.keys.filter { $0.hasPrefix(prefix) }.sorted()
    }
}

public enum ContentError: Error, Equatable, Sendable {
    case noPacks
    case versionMismatch(locale: String, version: Int, expected: Int)
    case duplicateLocale(String)
}

/// All packs bundled with the app. Fails fast if locales disagree on the release version.
public final class ContentLibrary {
    public let releaseVersion: Int
    private let packs: [String: ContentPack]
    /// IDs that were requested but missing (logged for diagnostics; never crashes).
    public private(set) var missingLog: [String] = []

    public static let fallbackText: [String: String] = [
        "zh-Hans": "（内容缺失）",
        "en": "(content missing)",
    ]

    public init(packs list: [ContentPack]) throws {
        guard let first = list.first else { throw ContentError.noPacks }
        var map: [String: ContentPack] = [:]
        for p in list {
            if map[p.locale] != nil { throw ContentError.duplicateLocale(p.locale) }
            if p.version != first.version {
                throw ContentError.versionMismatch(locale: p.locale, version: p.version, expected: first.version)
            }
            map[p.locale] = p
        }
        self.packs = map
        self.releaseVersion = first.version
    }

    public var locales: [String] { packs.keys.sorted() }

    public func pack(for locale: ContentLocale) -> ContentPack? { packs[locale.rawValue] }

    public func supports(_ locale: ContentLocale) -> Bool { packs[locale.rawValue] != nil }

    /// Text for an ID in the room's content locale, with a safe fallback when missing.
    public func text(_ id: String, locale: ContentLocale) -> String {
        if let t = packs[locale.rawValue]?.entries[id] { return t }
        // Fall back to the default language before the generic placeholder.
        if let t = packs[ContentLocale.zhHans.rawValue]?.entries[id] {
            missingLog.append("\(locale.rawValue):\(id)")
            return t
        }
        missingLog.append("\(locale.rawValue):\(id)")
        return ContentLibrary.fallbackText[locale.rawValue] ?? "—"
    }
}

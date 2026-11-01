import Foundation

// UGC safety without a backend:
//  1. Text filter for nicknames / custom text (term lists come from Content/<locale>/blocklist.json).
//  2. Reports go to the HOST inside the room. Host can clear content or kick.
//  3. Reports are kept in memory only and wiped at game end.
//  4. Each player can hide another player's content on THIS device only.
//  5. Serious cases: the app opens a mail draft the user sends themselves (no upload).

public struct TextFilter: Sendable {
    public static let maxNicknameLength = 12

    private let blocked: [String]

    public init(blockedTerms: [String]) {
        self.blocked = blockedTerms
            .map { TextFilter.normalize($0) }
            .filter { !$0.isEmpty }
    }

    /// Lowercase, remove whitespace/punctuation/symbols so "a.b c" style evasion still matches.
    public static func normalize(_ s: String) -> String {
        let scalars = s.lowercased().unicodeScalars.filter { scalar in
            !CharacterSet.whitespacesAndNewlines.contains(scalar)
                && !CharacterSet.punctuationCharacters.contains(scalar)
                && !CharacterSet.symbols.contains(scalar)
        }
        var view = String.UnicodeScalarView()
        view.append(contentsOf: scalars)
        return String(view)
    }

    public enum Verdict: Equatable, Sendable {
        case ok(String)          // cleaned text
        case empty
        case tooLong
        case blocked
    }

    public func checkNickname(_ raw: String) -> Verdict {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return .empty }
        if trimmed.count > TextFilter.maxNicknameLength { return .tooLong }
        return containsBlocked(trimmed) ? .blocked : .ok(trimmed)
    }

    public func checkFreeText(_ raw: String, maxLength: Int = 80) -> Verdict {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return .empty }
        if trimmed.count > maxLength { return .tooLong }
        return containsBlocked(trimmed) ? .blocked : .ok(trimmed)
    }

    public func containsBlocked(_ text: String) -> Bool {
        let n = TextFilter.normalize(text)
        return blocked.contains { n.contains($0) }
    }
}

public enum ReportedContent: String, Codable, Sendable {
    case nickname, drawing, customText, title
}

public struct Report: Codable, Equatable, Identifiable, Sendable {
    public let id: Int
    public let reporter: PlayerID
    public let target: PlayerID
    public let content: ReportedContent
    public let round: Int
    public init(id: Int, reporter: PlayerID, target: PlayerID, content: ReportedContent, round: Int) {
        self.id = id
        self.reporter = reporter
        self.target = target
        self.content = content
        self.round = round
    }
}

/// Host-side, memory-only report inbox.
public struct ReportInbox: Equatable, Sendable {
    public private(set) var reports: [Report] = []
    private var nextID = 1

    public init() {}

    @discardableResult
    public mutating func submit(reporter: PlayerID, target: PlayerID, content: ReportedContent, round: Int) -> Report? {
        guard reporter != target else { return nil }
        let r = Report(id: nextID, reporter: reporter, target: target, content: content, round: round)
        nextID += 1
        reports.append(r)
        return r
    }

    public mutating func dismiss(_ id: Int) { reports.removeAll { $0.id == id } }

    /// Call at game end.
    public mutating func wipe() {
        reports.removeAll()
        nextID = 1
    }

    public func count(against player: PlayerID) -> Int {
        reports.filter { $0.target == player }.count
    }
}

/// Per-device hide list (never sent anywhere).
public struct LocalHideList: Codable, Equatable, Sendable {
    private var hidden: [PlayerID: Set<ReportedContent>] = [:]
    public init() {}

    public mutating func hide(_ player: PlayerID, content: ReportedContent) {
        hidden[player, default: []].insert(content)
    }

    public mutating func unhide(_ player: PlayerID, content: ReportedContent) {
        hidden[player]?.remove(content)
    }

    public func isHidden(_ player: PlayerID, content: ReportedContent) -> Bool {
        hidden[player]?.contains(content) ?? false
    }

    public mutating func wipe() { hidden.removeAll() }
}

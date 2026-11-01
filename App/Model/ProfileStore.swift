import Foundation
import FrenemyCore

/// Local player profile: a random id, a nickname and an original avatar. No account, no contacts, no photos.
/// Stored in UserDefaults (Required Reason API, reason CA92.1 — declared in PrivacyInfo.xcprivacy).
final class ProfileStore: ObservableObject {
    private enum Key {
        static let playerID = "profile.playerID"
        static let nickname = "profile.nickname"
        static let avatar = "profile.avatar"
        static let onboarded = "profile.onboarded"
    }

    private let defaults: UserDefaults

    /// 8 hex chars. Used as MCPeerID.displayName, so it must stay short and ASCII.
    let playerID: PlayerID

    @Published var nickname: String {
        didSet { defaults.set(nickname, forKey: Key.nickname) }
    }

    @Published var avatar: Avatar {
        didSet {
            if let data = try? Codec.encode(avatar) { defaults.set(data, forKey: Key.avatar) }
        }
    }

    @Published var hasOnboarded: Bool {
        didSet { defaults.set(hasOnboarded, forKey: Key.onboarded) }
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults

        if let id = defaults.string(forKey: Key.playerID), !id.isEmpty {
            playerID = id
        } else {
            let id = String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(8)).lowercased()
            defaults.set(id, forKey: Key.playerID)
            playerID = id
        }

        nickname = defaults.string(forKey: Key.nickname) ?? ""
        if let data = defaults.data(forKey: Key.avatar), let a = try? Codec.decode(Avatar.self, from: data) {
            avatar = a
        } else {
            avatar = Avatar(shape: Int.random(in: 0..<4), face: Int.random(in: 0..<3), colorIndex: Int.random(in: 0..<6))
        }
        hasOnboarded = defaults.bool(forKey: Key.onboarded)
    }

    /// Nickname actually sent to a room: trimmed, never empty (the host filters it again).
    var effectiveNickname: String {
        let t = nickname.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? "P-\(playerID.prefix(3))" : String(t.prefix(TextFilter.maxNicknameLength))
    }
}

import Foundation
import FrenemyCore

/// Loads every Content/content.<locale>.json from the app bundle into one ContentLibrary.
/// All packs must carry the same release version (ContentLibrary enforces this); that version is what
/// the join handshake compares, so two phones with different builds are told to update instead of
/// silently showing different texts.
enum ContentLoader {
    static let locales = ["zh-Hans", "en"]

    static func load(bundle: Bundle = .main) -> (library: ContentLibrary?, problem: String?) {
        var packs: [ContentPack] = []
        for locale in locales {
            guard let url = bundle.url(forResource: "content.\(locale)", withExtension: "json") else {
                return (nil, "missing content.\(locale).json")
            }
            do {
                packs.append(try ContentPack.decode(Data(contentsOf: url)))
            } catch {
                return (nil, "content.\(locale).json: \(error.localizedDescription)")
            }
        }
        do {
            return (try ContentLibrary(packs: packs), nil)
        } catch {
            return (nil, "content packs disagree: \(error)")
        }
    }

    /// Reads the blocklist seed (one term per line, '#' comments). The seed is a starting point that
    /// MUST be reviewed by a native speaker of each language before submission.
    static func loadBlocklist(bundle: Bundle = .main) -> TextFilter {
        var terms: [String] = []
        for name in ["blocklist.zh-Hans", "blocklist.en"] {
            guard let url = bundle.url(forResource: name, withExtension: "txt"),
                  let text = try? String(contentsOf: url, encoding: .utf8) else { continue }
            for line in text.split(whereSeparator: \.isNewline) {
                let t = line.trimmingCharacters(in: .whitespaces)
                if !t.isEmpty, !t.hasPrefix("#") { terms.append(t) }
            }
        }
        return TextFilter(blockedTerms: terms)
    }
}

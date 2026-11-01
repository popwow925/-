import SwiftUI
import FrenemyCore

/// Links are placeholders until the user supplies the real URLs / address (see README "待你提供").
enum SupportInfo {
    static let privacyPolicyURL = URL(string: "https://example.com/frenemy/privacy")!   // TODO: replace before submission
    static let supportEmail = "support@example.com"                                     // TODO: replace before submission
}

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    var body: some View {
        NavigationStack {
            Form {
                Section(L("settings.privacy")) {
                    Text(L("settings.privacy_body")).font(.footnote)
                    Link(L("settings.privacy_policy"), destination: SupportInfo.privacyPolicyURL)
                }
                Section(L("settings.support")) {
                    // User-initiated only: nothing is sent unless the user presses send in Mail.
                    Button(L("settings.contact")) { openURL(mailURL) }
                }
                Section(L("settings.safety")) {
                    Text(L("settings.safety_body")).font(.footnote)
                }
                Section {
                    Text(L("settings.version") + " " + versionString).font(.footnote).foregroundColor(.secondary)
                }
            }
            .navigationTitle(L("settings.title"))
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("common.done")) { dismiss() } } }
        }
    }

    private var versionString: String {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let b = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return "\(v) (\(b))"
    }

    private var mailURL: URL {
        var c = URLComponents()
        c.scheme = "mailto"
        c.path = SupportInfo.supportEmail
        c.queryItems = [URLQueryItem(name: "subject", value: L("settings.mail_subject"))]
        return c.url ?? URL(string: "mailto:\(SupportInfo.supportEmail)")!
    }
}

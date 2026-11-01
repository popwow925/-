import SwiftUI
import FrenemyCore

/// UI string lookup. Keys live in Resources/Localizable.xcstrings (zh-Hans + en). A script in
/// tools/check_strings.py verifies that every key used here exists in the catalog.
func L(_ key: String) -> String {
    NSLocalizedString(key, comment: "")
}

func LF(_ key: String, _ args: CVarArg...) -> String {
    String(format: NSLocalizedString(key, comment: ""), arguments: args)
}

enum Palette {
    static let avatarColors: [Color] = [
        Color(red: 0.95, green: 0.45, blue: 0.40),
        Color(red: 0.98, green: 0.72, blue: 0.30),
        Color(red: 0.45, green: 0.78, blue: 0.45),
        Color(red: 0.35, green: 0.70, blue: 0.90),
        Color(red: 0.62, green: 0.50, blue: 0.90),
        Color(red: 0.92, green: 0.50, blue: 0.75)
    ]
    static func color(_ index: Int) -> Color {
        avatarColors[((index % avatarColors.count) + avatarColors.count) % avatarColors.count]
    }
}

/// Original avatar: a colored shape with a simple face. No photos, no contacts, no permissions.
struct AvatarView: View {
    let avatar: Avatar
    var size: CGFloat = 44
    var dimmed = false

    private static let faces = ["•‿•", "•_•", "°o°"]

    var body: some View {
        ZStack {
            shape.fill(Palette.color(avatar.colorIndex))
            Text(AvatarView.faces[((avatar.face % 3) + 3) % 3])
                .font(.system(size: size * 0.34, weight: .bold, design: .rounded))
                .foregroundColor(.white)
        }
        .frame(width: size, height: size)
        .opacity(dimmed ? 0.35 : 1)
        .accessibilityHidden(true)
    }

    private var shape: AnyShape {
        switch ((avatar.shape % 4) + 4) % 4 {
        case 0: return AnyShape(Circle())
        case 1: return AnyShape(RoundedRectangle(cornerRadius: size * 0.28))
        case 2: return AnyShape(Capsule())
        default: return AnyShape(Rectangle().rotation(.degrees(45)).scale(0.78))
        }
    }
}

struct BigButtonStyle: ButtonStyle {
    var tint: Color = .accentColor
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.title3.weight(.semibold))
            .frame(maxWidth: .infinity, minHeight: 56)
            .background(tint.opacity(configuration.isPressed ? 0.75 : 1))
            .foregroundColor(.white)
            .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}

struct PlayerChip: View {
    let player: PublicPlayer
    let displayName: String
    var big = false

    var body: some View {
        HStack(spacing: 10) {
            AvatarView(avatar: player.avatar, size: big ? 56 : 40, dimmed: !player.isConnected)
            VStack(alignment: .leading, spacing: 2) {
                Text(displayName)
                    .font(big ? .title2.weight(.semibold) : .headline)
                    .lineLimit(1)
                HStack(spacing: 8) {
                    Label("\(player.crystals)", systemImage: "diamond.fill")
                    if player.shields > 0 { Label("\(player.shields)", systemImage: "shield.fill") }
                    if !player.isConnected { Image(systemName: "wifi.slash") }
                }
                .font(big ? .title3 : .subheadline)
                .foregroundColor(.secondary)
            }
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }
}

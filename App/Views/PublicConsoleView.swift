import SwiftUI
import FrenemyCore

/// 公共控制台 — renders PublicState ONLY. Used for:
///   - the TV / projector (external display scene or iPhone/iPad screen mirroring)
///   - the host device in .host mode
///   - the public area on a player's phone
/// It has no access to PrivateState, so a secret cannot leak onto the shared screen by construction.
struct PublicConsoleView: View {
    @EnvironmentObject var model: AppModel
    /// TV style: bigger type, no controls.
    var tvStyle = false
    var showHostControls = false

    var body: some View {
        if let s = model.publicState {
            VStack(spacing: tvStyle ? 24 : 14) {
                header(s)
                if let g = s.miniGame, [.miniGame, .finale].contains(s.phase) {
                    Text(L("game.\(g.rawValue)")).font(tvStyle ? .system(size: 56, weight: .heavy, design: .rounded) : .title.bold())
                    Text(L("gamekind.\(g.kind.rawValue)")).foregroundColor(.secondary)
                }
                eventList(s)
                rulesList(s)
                playerGrid(s)
                if s.phase == .results || s.phase == .ended { standings(s) }
                if s.phase == .ended { missions(s) }
                Spacer(minLength: 0)
                if showHostControls { hostBar(s) }
            }
            .padding(tvStyle ? 40 : 16)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color(.systemBackground))
        } else {
            ProgressView()
        }
    }

    // MARK: Pieces

    private func header(_ s: PublicState) -> some View {
        VStack(spacing: 4) {
            if s.round > 0 {
                Text(LF("console.round", s.round, s.totalRounds))
                    .font(tvStyle ? .title : .subheadline).foregroundColor(.secondary)
            }
            Text(L("phase.\(s.phase.rawValue)"))
                .font(tvStyle ? .system(size: 64, weight: .heavy, design: .rounded) : .largeTitle.bold())
                .multilineTextAlignment(.center)
            if s.isLastRound && s.phase != .lobby && s.phase != .ended {
                Text(L("console.last_round")).font(tvStyle ? .title2 : .subheadline).foregroundColor(.orange)
            }
        }
    }

    private func eventList(_ s: PublicState) -> some View {
        VStack(spacing: 6) {
            ForEach(Array(s.events.enumerated()), id: \.offset) { _, e in
                Text(render(e)).font(tvStyle ? .title2 : .body).multilineTextAlignment(.center)
            }
        }
    }

    private func rulesList(_ s: PublicState) -> some View {
        VStack(spacing: 4) {
            ForEach(s.activeRules.keys.sorted(), id: \.self) { pid in
                Label("\(model.name(of: pid)): \(model.text(s.activeRules[pid] ?? ""))", systemImage: "exclamationmark.bubble")
                    .font(tvStyle ? .title3 : .footnote)
                    .foregroundColor(.orange)
            }
        }
    }

    private func playerGrid(_ s: PublicState) -> some View {
        let cols = [GridItem(.adaptive(minimum: tvStyle ? 320 : 170), spacing: 12)]
        return LazyVGrid(columns: cols, spacing: 12) {
            ForEach(s.players) { p in
                VStack(alignment: .leading, spacing: 4) {
                    PlayerChip(player: p, displayName: model.name(of: p.id), big: tvStyle)
                    if s.phase != .lobby {
                        Text("\(L("console.tile")): \(L("tile.\(Board.layout[p.position % Board.size].rawValue)"))")
                            .font(tvStyle ? .headline : .caption).foregroundColor(.secondary)
                    }
                    ForEach(p.shownTitleIDs, id: \.self) { t in
                        Text("🏷 " + model.text(t)).font(tvStyle ? .headline : .caption)
                    }
                }
                .padding(10)
                .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14))
            }
        }
    }

    private func standings(_ s: PublicState) -> some View {
        VStack(spacing: 6) {
            Text(L("console.standings")).font(.headline)
            ForEach(s.standings ?? [], id: \.player) { r in
                Text("#\(r.rank)  \(model.name(of: r.player))").font(tvStyle ? .title2 : .body)
            }
        }
    }

    private func missions(_ s: PublicState) -> some View {
        VStack(spacing: 6) {
            if let list = s.revealedMissions, !list.isEmpty {
                Text(L("console.missions_reveal")).font(.headline)
                ForEach(Array(list.enumerated()), id: \.offset) { _, m in
                    Text("\(L("console.round_short")) \(m.round) · \(model.name(of: m.holder)): \(model.text(m.missionID)) (\(L("mission.outcome.\(m.outcome.rawValue)")))")
                        .font(tvStyle ? .title3 : .footnote)
                }
            }
        }
    }

    private func hostBar(_ s: PublicState) -> some View {
        VStack(spacing: 8) {
            if s.phase == .ended {
                Button(L("common.close_room")) { model.leave() }.buttonStyle(BigButtonStyle(tint: .gray))
            } else {
                if !model.canContinue, let e = model.hostEngine {
                    Text("\(L("console.waiting_for")) " + e.pendingScorePlayers.map { model.name(of: $0) }.joined(separator: ", "))
                        .font(.footnote).foregroundColor(.secondary)
                }
                Button(L("console.continue")) { model.hostContinue() }
                    .buttonStyle(BigButtonStyle())
                    .disabled(!model.canContinue)
            }
        }
    }

    // MARK: Event text

    /// Fills "{player}"-style placeholders. Names, titles and card names are resolved on THIS device
    /// from the room's content pack; the wire only carried IDs.
    private func render(_ e: PublicEvent) -> String {
        var text = model.text(e.key)
        for (k, v) in e.params {
            let value: String
            switch k {
            case "player", "by", "target": value = model.name(of: v)
            case "title": value = model.text(v)
            case "card": value = model.text("card.\(v)")
            default: value = v
            }
            text = text.replacingOccurrences(of: "{\(k)}", with: value)
        }
        return text
    }
}

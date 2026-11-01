import SwiftUI
import FrenemyCore

// The phone a player holds. Two tabs: "我的" (private panel) and "大屏" (the same public console the TV shows).
// Motion mini-games switch to a minimal "look at the TV" screen: nobody shakes the phone while reading it.

struct PlayerGameView: View {
    @EnvironmentObject var model: AppModel
    @State private var tab = 0

    var body: some View {
        if let pub = model.publicState {
            VStack(spacing: 0) {
                if isMotionPhase(pub) {
                    MotionHintView(pub: pub)
                } else {
                    Picker("", selection: $tab) {
                        Text(L("player.mine")).tag(0)
                        Text(L("player.screen")).tag(1)
                    }
                    .pickerStyle(.segmented)
                    .padding([.horizontal, .top])
                    if tab == 0 {
                        ScrollView { PrivatePanel(pub: pub).padding() }
                    } else {
                        ScrollView { PublicConsoleView() }
                    }
                }
            }
        } else {
            ProgressView()
        }
    }

    private func isMotionPhase(_ pub: PublicState) -> Bool {
        guard [.miniGame, .finale].contains(pub.phase), let g = pub.miniGame else { return false }
        return g.kind == .motion
    }
}

// MARK: - Motion phase

struct MotionHintView: View {
    @EnvironmentObject var model: AppModel
    let pub: PublicState
    @State private var submittedKey: String?

    var body: some View {
        let key = "\(pub.round)-\(pub.phase.rawValue)"
        VStack(spacing: 18) {
            Spacer()
            Image(systemName: "tv").font(.system(size: 64))
            Text(L("motion.look_at_tv")).font(.system(size: 34, weight: .heavy, design: .rounded))
            if let g = pub.miniGame {
                Text(L("game.\(g.rawValue)")).font(.title2).foregroundColor(.secondary)
                if let rule = pub.activeRules[model.myID] {
                    Label(model.text(rule), systemImage: "exclamationmark.bubble").foregroundColor(.orange)
                }
                if submittedKey == key {
                    Label(L("minigame.submitted"), systemImage: "checkmark.circle.fill").foregroundColor(.green)
                } else {
                    // M0 stand-in: taps replace shaking. CoreMotion arrives in M1a together with
                    // NSMotionUsageDescription; until then the hint screen keeps the same layout.
                    TapChallengeView(lowerIsBetter: g.lowerIsBetter) { score in
                        submittedKey = key
                        model.send(.score(score))
                    }
                }
            }
            Spacer()
            ConnectionBar()
        }
        .padding()
    }
}

struct TapChallengeView: View {
    let lowerIsBetter: Bool
    let onSubmit: (Int) -> Void
    @State private var taps = 0
    @State private var remaining = 5
    @State private var running = false
    @State private var finished = false
    private let ticker = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 12) {
            Text(L(lowerIsBetter ? "stub.dont_tap" : "stub.tap_fast")).font(.footnote).foregroundColor(.secondary)
            if running {
                Text("\(remaining)").font(.system(size: 48, weight: .bold, design: .rounded)).monospacedDigit()
            }
            Button { if running { taps += 1 } else { running = true } } label: {
                Text(running ? L("stub.tap") : L("stub.ready"))
                    .frame(maxWidth: .infinity, minHeight: 120)
            }
            .buttonStyle(BigButtonStyle(tint: running ? .pink : .accentColor))
        }
        .onReceive(ticker) { _ in
            guard running, !finished else { return }
            remaining -= 1
            if remaining <= 0 {
                finished = true
                onSubmit(taps)
            }
        }
    }
}

struct ConnectionBar: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        HStack {
            Image(systemName: model.connectionEnded ? "wifi.slash" : "wifi")
            Text(model.connectionEnded ? L("player.disconnected") : L("player.connected"))
        }
        .font(.footnote)
        .foregroundColor(model.connectionEnded ? .red : .secondary)
    }
}

// MARK: - Private panel

struct PrivatePanel: View {
    @EnvironmentObject var model: AppModel
    let pub: PublicState
    @State private var submittedKey: String?
    @State private var cardChoice: CardKind = .reverseDice
    @State private var target: PlayerID?
    @State private var playedCardRound = 0
    @State private var stake = 1
    @State private var votedRound = 0

    private var me: PublicPlayer? { pub.players.first { $0.id == model.myID } }
    private var others: [PublicPlayer] { pub.players.filter { $0.id != model.myID } }
    private var priv: PrivateState? { model.privateState }

    var body: some View {
        VStack(spacing: 16) {
            if let me = me {
                PlayerChip(player: me, displayName: model.name(of: me.id), big: true)
                    .padding(12)
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
            }
            if model.connectionEnded && pub.phase != .ended { ConnectionBar() }
            phasePanel
            secretCards
            moderationMenu
        }
    }

    // MARK: Per phase

    @ViewBuilder private var phasePanel: some View {
        switch pub.phase {
        case .lobby:
            Label(L("join.waiting_start"), systemImage: "hourglass")
        case .roundStart, .roll, .move, .payout:
            Label(L("motion.look_at_tv"), systemImage: "tv").font(.title2)
        case .tile:
            tilePanel
        case .miniGame, .finale:
            miniGamePanel
        case .sabotage:
            sabotagePanel
        case .award:
            awardPanel
        case .betting:
            bettingPanel
        case .results, .ended:
            if let r = pub.standings?.first(where: { $0.player == model.myID }) {
                Text("#\(r.rank)").font(.system(size: 72, weight: .heavy, design: .rounded))
            }
        }
    }

    private var tilePanel: some View {
        let shop = pub.events.contains { $0.key == "tile.shop" && $0.params["player"] == model.myID }
        let canBuy = (me?.crystals ?? 0) >= Board.shieldPrice && (me?.shields ?? 0) < Limits.maxShields
        return VStack(spacing: 10) {
            Label(L("motion.look_at_tv"), systemImage: "tv")
            if shop {
                Button("\(L("shop.buy_shield")) (\(Board.shieldPrice))") { model.send(.buyShield) }
                    .buttonStyle(BigButtonStyle(tint: .indigo)).disabled(!canBuy)
            }
        }
    }

    private var miniGamePanel: some View {
        let key = "\(pub.round)-\(pub.phase.rawValue)"
        return VStack(spacing: 12) {
            if let g = pub.miniGame { Text(L("game.\(g.rawValue)")).font(.title.bold()) }
            if let word = priv?.word {
                VStack {
                    Text(L("minigame.your_word")).font(.footnote).foregroundColor(.secondary)
                    Text(wordText(word)).font(.system(size: 40, weight: .heavy, design: .rounded))
                }
                .padding().frame(maxWidth: .infinity)
                .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
            }
            if submittedKey == key {
                Label(L("minigame.submitted"), systemImage: "checkmark.circle.fill").foregroundColor(.green)
            } else {
                TapChallengeView(lowerIsBetter: pub.miniGame?.lowerIsBetter ?? false) { score in
                    submittedKey = key
                    model.send(.score(score))
                }
            }
        }
    }

    private func wordText(_ w: PrivateWord) -> String {
        guard let pair = model.content?.pack(for: pub.contentLocale)?.wordPairs.first(where: { $0.id == w.pairID }) else { return "?" }
        return w.side == .civilian ? pair.civilian : pair.undercover
    }

    private var sabotagePanel: some View {
        let usable = (priv?.vouchers ?? []).filter { pub.round >= $0.usableFromRound && pub.round <= $0.lastUsableRound }
        let kinds = CardKind.allCases.filter { $0.tier == .light || pub.strongSabotageEnabled }
        return VStack(spacing: 10) {
            Text(L("sabotage.title")).font(.title2.bold())
            if pub.isLastRound {
                Text(L("sabotage.last_round")).foregroundColor(.secondary)
            } else if usable.isEmpty {
                Text(L("sabotage.no_voucher")).foregroundColor(.secondary)
            } else {
                Text(LF("sabotage.vouchers", usable.count)).font(.footnote).foregroundColor(.secondary)
                Picker(L("sabotage.card"), selection: $cardChoice) {
                    ForEach(kinds, id: \.self) { Text(model.text("card.\($0.rawValue)")).tag($0) }
                }
                .pickerStyle(.menu)
                TargetPicker(players: others, selection: $target)
                Button(L("sabotage.play")) {
                    if let t = target {
                        model.send(.playCard(cardChoice, target: t))
                        playedCardRound = pub.round
                    }
                }
                .buttonStyle(BigButtonStyle(tint: .red))
                .disabled(target == nil || playedCardRound == pub.round)
                Text(L("sabotage.shield_hint")).font(.footnote).foregroundColor(.secondary)
            }
        }
    }

    private var awardPanel: some View {
        VStack(spacing: 10) {
            Text(L("award.title")).font(.title2.bold())
            if let prompt = priv?.awardPrompt {
                Text(model.text(prompt.titleID)).font(.title.bold())
                Text(L("award.you_won")).foregroundColor(.secondary)
                HStack {
                    Button(L("award.accept")) { model.send(.awardAccept) }.buttonStyle(BigButtonStyle(tint: .green))
                    Button(L("award.decline")) { model.send(.awardDecline) }.buttonStyle(BigButtonStyle(tint: .gray))
                }
            } else {
                if let q = pub.events.first(where: { $0.key.hasPrefix("prompt.") }) {
                    Text(model.text(q.key)).font(.title.bold()).multilineTextAlignment(.center)
                }
                Text(L("award.vote_hint")).foregroundColor(.secondary)
                TargetPicker(players: others, selection: $target)
                Button(L("award.vote")) {
                    if let t = target { model.send(.awardVote(target: t)); votedRound = pub.round }
                }
                .buttonStyle(BigButtonStyle())
                .disabled(target == nil || votedRound == pub.round)
            }
        }
    }

    private var bettingPanel: some View {
        let crystals = me?.crystals ?? 0
        let table = Dictionary(uniqueKeysWithValues: pub.players.map { ($0.id, $0.crystals) })
        let rank = Ranking.compete(scores: table).first { $0.player == model.myID }?.rank ?? pub.players.count
        let cap = FinalBetRules.maxStake(crystals: crystals, rank: rank, playerCount: pub.players.count)
        return VStack(spacing: 10) {
            Text(L("bet.title")).font(.title2.bold())
            if let b = priv?.bet {
                Label("\(model.name(of: b.target)) · \(b.stake)", systemImage: "checkmark.seal.fill").foregroundColor(.green)
                Text(L("bet.placed_hint")).font(.footnote).foregroundColor(.secondary)
            } else if cap < 1 {
                Text(L("bet.no_stake")).foregroundColor(.secondary)
            } else {
                Text(L("bet.hint")).font(.footnote).foregroundColor(.secondary)
                TargetPicker(players: others, selection: $target)
                Stepper("\(L("bet.stake")): \(min(max(stake, 1), cap)) / \(cap)", value: $stake, in: 1...cap)
                Button(L("bet.place")) {
                    if let t = target { model.send(.bet(target: t, stake: min(max(stake, 1), cap))) }
                }
                .buttonStyle(BigButtonStyle(tint: .purple)).disabled(target == nil)
            }
        }
    }

    // MARK: Secret cards (visible only here)

    @ViewBuilder private var secretCards: some View {
        if let m = priv?.mission {
            VStack(alignment: .leading, spacing: 4) {
                Label(L("mission.secret"), systemImage: "eye.slash")
                Text(model.text(m.missionID)).font(.headline)
                Text(L("mission.secret_hint")).font(.footnote).foregroundColor(.secondary)
            }
            .padding().frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.yellow.opacity(0.18), in: RoundedRectangle(cornerRadius: 14))
        }
    }

    // MARK: Moderation (UGC controls required by App Review 1.2)

    @ViewBuilder private var moderationMenu: some View {
        if !others.isEmpty {
            Menu {
                ForEach(others) { p in
                    Menu(model.name(of: p.id)) {
                        Button(L("moderation.report")) { model.report(p.id, .nickname) }
                        Button(L("moderation.hide")) { model.hideList.hide(p.id, content: .nickname) }
                    }
                }
            } label: {
                Label(L("moderation.menu"), systemImage: "flag")
            }
            .font(.footnote)
        }
    }
}

struct TargetPicker: View {
    @EnvironmentObject var model: AppModel
    let players: [PublicPlayer]
    @Binding var selection: PlayerID?

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(players) { p in
                    Button { selection = p.id } label: {
                        VStack(spacing: 4) {
                            AvatarView(avatar: p.avatar, size: 48)
                            Text(model.name(of: p.id)).font(.caption).lineLimit(1)
                        }
                        .padding(8)
                        .background(selection == p.id ? Color.accentColor.opacity(0.25) : Color.clear,
                                    in: RoundedRectangle(cornerRadius: 12))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

import SwiftUI
import FrenemyCore

// MARK: - Create room (host)

struct CreateRoomSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var config = GameConfig()

    var body: some View {
        NavigationStack {
            Form {
                Section(L("create.rounds")) {
                    Picker(L("create.rounds"), selection: $config.totalRounds) {
                        ForEach(GameConfig.allowedRounds, id: \.self) { Text("\($0)").tag($0) }
                    }.pickerStyle(.segmented)
                }
                Section(L("create.language")) {
                    Picker(L("create.language"), selection: $config.contentLocale) {
                        Text("简体中文").tag(ContentLocale.zhHans)
                        Text("English").tag(ContentLocale.en)
                    }.pickerStyle(.segmented)
                    Text(L("create.language_hint")).font(.footnote).foregroundColor(.secondary)
                }
                Section {
                    Toggle(L("create.missions"), isOn: $config.secretMissionsEnabled)
                    Toggle(L("create.strong_cards"), isOn: $config.strongSabotageEnabled)
                } footer: {
                    Text(L("create.friendly_hint"))
                }
                Section { Text(L("create.network_hint")).font(.footnote).foregroundColor(.secondary) }
            }
            .navigationTitle(L("home.create"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("create.open")) {
                        model.createRoom(config: config)
                        dismiss()
                    }
                }
            }
        }
    }
}

// MARK: - Host lobby (shown on the host device / mirrored to the TV)

struct HostLobbyView: View {
    @EnvironmentObject var model: AppModel
    @State private var confirmClose = false

    var body: some View {
        let state = model.publicState
        VStack(spacing: 18) {
            Text(L("lobby.room_code")).font(.headline).foregroundColor(.secondary)
            Text(state?.roomCode ?? "----")
                .font(.system(size: 84, weight: .heavy, design: .rounded))
                .monospacedDigit()
                .accessibilityLabel("\(L("lobby.room_code")) \(state?.roomCode ?? "")")
            Text(L("lobby.join_hint")).multilineTextAlignment(.center).foregroundColor(.secondary)

            let players = state?.players ?? []
            List {
                Section("\(L("lobby.players")) \(players.count)/\(Limits.maxPlayers)") {
                    ForEach(players) { p in
                        HStack {
                            PlayerChip(player: p, displayName: model.name(of: p.id))
                            Button(role: .destructive) { model.kick(p.id) } label: {
                                Image(systemName: "person.fill.xmark")
                            }
                            .accessibilityLabel(L("lobby.kick"))
                        }
                    }
                    if players.isEmpty { Text(L("lobby.waiting")).foregroundColor(.secondary) }
                }
            }
            .listStyle(.insetGrouped)

            Button(L("lobby.start")) { model.startRoomGame() }
                .buttonStyle(BigButtonStyle())
                .disabled(players.count < Limits.minPlayers)
            if players.count < Limits.minPlayers {
                Text(L("lobby.need_two")).font(.footnote).foregroundColor(.secondary)
            }
            Button(L("common.close_room"), role: .destructive) { confirmClose = true }
        }
        .padding()
        .confirmationDialog(L("common.close_room_confirm"), isPresented: $confirmClose, titleVisibility: .visible) {
            Button(L("common.close_room"), role: .destructive) { model.leave() }
        }
    }
}

// MARK: - Join (player phone)

struct JoinView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        NavigationStack {
            VStack(spacing: 14) {
                if let reason = model.rejectReason {
                    Label(rejectText(reason), systemImage: "exclamationmark.triangle.fill")
                        .foregroundColor(.orange)
                        .padding().frame(maxWidth: .infinity)
                        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
                }
                if let text = model.failureText {
                    Text(text).font(.footnote).foregroundColor(.red)
                }

                if model.rooms.isEmpty {
                    ProgressView()
                    Text(L("join.searching")).foregroundColor(.secondary)
                    Text(L("join.same_wifi")).font(.footnote).foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                } else {
                    List(model.rooms) { room in
                        Button { model.join(room) } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(room.hostName).font(.headline)
                                    Text("\(L("lobby.room_code")) \(room.roomCode)")
                                        .font(.subheadline).foregroundColor(.secondary)
                                }
                                Spacer()
                                Image(systemName: "chevron.right")
                            }
                        }
                    }
                }
                Spacer()
                if model.connectedToRoom {
                    Label(L("join.waiting_start"), systemImage: "checkmark.circle.fill").foregroundColor(.green)
                }
            }
            .padding()
            .navigationTitle(L("home.join"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("common.back")) { model.leave() } }
            }
        }
    }

    private func rejectText(_ r: RejectReason) -> String {
        switch r {
        case .protocolMismatch, .contentPackMismatch: return L("reject.update_app")
        case .roomFull: return L("reject.room_full")
        case .gameInProgress: return L("reject.in_progress")
        case .kicked: return L("reject.kicked")
        }
    }
}

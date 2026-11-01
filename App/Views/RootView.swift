import SwiftUI
import FrenemyCore

struct RootView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        Group {
            switch model.mode {
            case .none:
                HomeView()
            case .demo:
                DemoView()
            case .host:
                HostRootView()
            case .client:
                ClientRootView()
            }
        }
        .alert(L("common.error"), isPresented: Binding(get: { model.failureText != nil && model.mode == .none },
                                                       set: { if !$0 { model.failureText = nil } })) {
            Button(L("common.done"), role: .cancel) {}
        } message: {
            Text(model.failureText ?? "")
        }
        #if DEBUG
        .onAppear {
            if let scene = AppModel.screenshotScene { model.runScreenshotScene(scene) }
        }
        #endif
        .onChange(of: model.mode) { _, new in
            // Keep the screen awake while a party is running; restore the system default afterwards.
            UIApplication.shared.isIdleTimerDisabled = (new != .none)
        }
    }
}

/// 单机演示: this device is host + player, bots fill the table. Fully offline.
struct DemoView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button(L("common.back")) { model.leave() }
                Spacer()
                Text(L("demo.badge")).font(.footnote.weight(.semibold))
                    .padding(.horizontal, 10).padding(.vertical, 4)
                    .background(Color.orange.opacity(0.2), in: Capsule())
            }
            .padding(.horizontal).padding(.top, 8)
            PlayerGameView()
            if let s = model.publicState, s.phase != .ended {
                VStack(spacing: 6) {
                    if !model.canContinue {
                        Text(L("demo.your_turn_first")).font(.footnote).foregroundColor(.secondary)
                    }
                    Button(L("console.continue")) { model.hostContinue() }
                        .buttonStyle(BigButtonStyle())
                        .disabled(!model.canContinue)
                }
                .padding()
            } else {
                Button(L("common.back")) { model.leave() }
                    .buttonStyle(BigButtonStyle(tint: .gray)).padding()
            }
        }
        .frame(maxWidth: 680)
        .frame(maxWidth: .infinity)
    }
}

/// Real room, host device: public console only. Mirror this screen to a TV, or use an external display.
struct HostRootView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        if let s = model.publicState, s.phase != .lobby {
            PublicConsoleView(tvStyle: false, showHostControls: true)
        } else {
            HostLobbyView()
        }
    }
}

struct ClientRootView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        if let s = model.publicState, s.phase != .lobby {
            VStack(spacing: 0) {
                PlayerGameView()
                if s.phase == .ended || model.connectionEnded {
                    Button(L("common.back")) { model.leave() }
                        .buttonStyle(BigButtonStyle(tint: .gray)).padding()
                }
            }
            .frame(maxWidth: 680)
            .frame(maxWidth: .infinity)
        } else {
            JoinView()
        }
    }
}

import SwiftUI
import UIKit
import FrenemyCore

/// The one shared model. The external-display scene lives in a different UIScene, so it cannot receive
/// the SwiftUI environment from the main WindowGroup; both read this instance.
enum AppContext {
    static let model = AppModel()
}

@main
struct FrenemyApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var model = AppContext.model

    var body: some Scene {
        WindowGroup {
            #if DEBUG
            if AppModel.screenshotScene == "tv" {
                // Screenshot of what the TV shows (external display), rendered full screen.
                TVRootView()
                    .environmentObject(model)
                    .onAppear { model.runScreenshotScene("tv") }
            } else {
                RootView()
                    .environmentObject(model)
                    .environmentObject(model.profile)
            }
            #else
            RootView()
                .environmentObject(model)
                .environmentObject(model.profile)
            #endif
        }
    }
}

// MARK: - External display (TV / projector connected as a second screen)

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        // iOS 16+: wired / wireless "extended display" arrives as a separate scene.
        // Plain screen MIRRORING does not create a scene; the host screen itself is then what the TV shows,
        // which is why the host screen only ever renders PublicState.
        if connectingSceneSession.role == .windowExternalDisplayNonInteractive {
            let config = UISceneConfiguration(name: "External", sessionRole: connectingSceneSession.role)
            config.delegateClass = ExternalDisplaySceneDelegate.self
            return config
        }
        return UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
    }
}

final class ExternalDisplaySceneDelegate: NSObject, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let root = TVRootView().environmentObject(AppContext.model)
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = UIHostingController(rootView: root)
        window.isHidden = false
        self.window = window
    }

    func sceneDidDisconnect(_ scene: UIScene) {
        window = nil
    }
}

/// What the TV shows. PublicState only: this view tree has no access to PrivateState rendering code.
struct TVRootView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        if let s = model.publicState {
            if s.phase == .lobby && model.mode == .host {
                VStack(spacing: 24) {
                    Text(L("app.title")).font(.system(size: 72, weight: .heavy, design: .rounded))
                    Text(L("lobby.room_code")).font(.title).foregroundColor(.secondary)
                    Text(s.roomCode).font(.system(size: 160, weight: .heavy, design: .rounded)).monospacedDigit()
                    Text("\(s.players.count)/\(Limits.maxPlayers)").font(.title)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                PublicConsoleView(tvStyle: true)
            }
        } else {
            VStack(spacing: 16) {
                Text(L("app.title")).font(.system(size: 72, weight: .heavy, design: .rounded))
                Text(L("tv.idle")).font(.title).foregroundColor(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

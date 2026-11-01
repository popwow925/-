import SwiftUI
import FrenemyCore

struct HomeView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var profile: ProfileStore
    @State private var showProfile = false
    @State private var showSettings = false
    @State private var showCreate = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    VStack(spacing: 6) {
                        Text(L("app.title")).font(.system(size: 40, weight: .heavy, design: .rounded))
                        Text(L("app.tagline")).font(.subheadline).foregroundColor(.secondary)
                    }
                    .padding(.top, 24)

                    Button { showProfile = true } label: {
                        HStack {
                            AvatarView(avatar: profile.avatar, size: 44)
                            Text(profile.effectiveNickname).font(.headline)
                            Spacer()
                            Image(systemName: "pencil")
                        }
                        .padding(12)
                        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(L("home.edit_profile"))

                    Button(L("home.demo")) { model.startDemo() }
                        .buttonStyle(BigButtonStyle(tint: .orange))
                    Text(L("home.demo_hint")).font(.footnote).foregroundColor(.secondary)

                    Button(L("home.create")) { showCreate = true }
                        .buttonStyle(BigButtonStyle())
                    Text(L("home.create_hint")).font(.footnote).foregroundColor(.secondary)

                    Button(L("home.join")) { model.beginBrowsing() }
                        .buttonStyle(BigButtonStyle(tint: .teal))

                    if let problem = model.contentProblem {
                        Text(problem).font(.caption).foregroundColor(.red)
                    }
                }
                .padding(.horizontal, 20)
                .frame(maxWidth: 520)
                .frame(maxWidth: .infinity)
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                        .accessibilityLabel(L("settings.title"))
                }
            }
            .sheet(isPresented: $showProfile) { ProfileEditor().environmentObject(profile) }
            .sheet(isPresented: $showSettings) { SettingsView().environmentObject(model) }
            .sheet(isPresented: $showCreate) { CreateRoomSheet().environmentObject(model) }
            .sheet(isPresented: .constant(!profile.hasOnboarded)) {
                OnboardingView().environmentObject(profile).interactiveDismissDisabled()
            }
        }
    }
}

struct ProfileEditor: View {
    @EnvironmentObject var profile: ProfileStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section(L("profile.nickname")) {
                    TextField(L("profile.nickname_placeholder"), text: $profile.nickname)
                        .onChange(of: profile.nickname) { _, new in
                            if new.count > TextFilter.maxNicknameLength {
                                profile.nickname = String(new.prefix(TextFilter.maxNicknameLength))
                            }
                        }
                }
                Section(L("profile.avatar")) {
                    HStack { Spacer(); AvatarView(avatar: profile.avatar, size: 88); Spacer() }
                    Picker(L("profile.shape"), selection: $profile.avatar.shape) {
                        ForEach(0..<4, id: \.self) { Text("\($0 + 1)").tag($0) }
                    }.pickerStyle(.segmented)
                    Picker(L("profile.face"), selection: $profile.avatar.face) {
                        ForEach(0..<3, id: \.self) { Text("\($0 + 1)").tag($0) }
                    }.pickerStyle(.segmented)
                    HStack {
                        ForEach(0..<Palette.avatarColors.count, id: \.self) { i in
                            Circle().fill(Palette.color(i)).frame(width: 34, height: 34)
                                .overlay(Circle().stroke(Color.primary, lineWidth: profile.avatar.colorIndex == i ? 3 : 0))
                                .onTapGesture { profile.avatar.colorIndex = i }
                                .accessibilityLabel("\(L("profile.color")) \(i + 1)")
                        }
                    }
                }
            }
            .navigationTitle(L("profile.title"))
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("common.done")) { dismiss() } } }
        }
    }
}

struct OnboardingView: View {
    @EnvironmentObject var profile: ProfileStore

    var body: some View {
        NavigationStack {
            VStack(spacing: 18) {
                Text(L("onboarding.title")).font(.title.bold())
                Text(L("onboarding.body")).multilineTextAlignment(.center).foregroundColor(.secondary)
                AvatarView(avatar: profile.avatar, size: 96)
                TextField(L("profile.nickname_placeholder"), text: $profile.nickname)
                    .textFieldStyle(.roundedBorder)
                    .onChange(of: profile.nickname) { _, new in
                        if new.count > TextFilter.maxNicknameLength {
                            profile.nickname = String(new.prefix(TextFilter.maxNicknameLength))
                        }
                    }
                Button(L("onboarding.start")) { profile.hasOnboarded = true }
                    .buttonStyle(BigButtonStyle())
                Text(L("onboarding.privacy")).font(.footnote).foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(24)
        }
    }
}

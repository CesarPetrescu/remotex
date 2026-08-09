import SwiftUI

struct ContentView: View {
    @StateObject private var viewModel = RemotexViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.session == nil {
                    HostsView(viewModel: viewModel)
                } else {
                    SessionView(viewModel: viewModel)
                }
            }
            .navigationTitle("Remotex")
            .toolbar {
                if viewModel.session != nil {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Close") {
                            viewModel.closeSession()
                        }
                    }
                }
            }
            .alert(
                "Remotex",
                isPresented: Binding(
                    get: { viewModel.errorMessage != nil },
                    set: { if !$0 { viewModel.errorMessage = nil } }
                )
            ) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(viewModel.errorMessage ?? "")
            }
        }
        .tint(.remotexAccent)
        .preferredColorScheme(.dark)
    }
}

private struct HostsView: View {
    @ObservedObject var viewModel: RemotexViewModel

    var body: some View {
        List {
            Section {
                TextField("Relay URL", text: $viewModel.relayURL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.system(.body, design: .monospaced))
                SecureField("User token", text: $viewModel.userToken)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.system(.body, design: .monospaced))
                Button {
                    viewModel.refreshHosts()
                } label: {
                    Label(
                        viewModel.status == .loading ? "Loading" : "Load hosts",
                        systemImage: "arrow.clockwise"
                    )
                }
            }

            Section("Hosts") {
                if viewModel.hosts.isEmpty {
                    EmptyStateRow(text: viewModel.status == .loading ? "loading" : "no hosts")
                } else {
                    ForEach(viewModel.hosts) { host in
                        Button {
                            viewModel.openSession(host: host)
                        } label: {
                            HostRow(host: host)
                        }
                        .disabled(!host.online)
                    }
                }
            }

            if !viewModel.threads.isEmpty {
                Section("Recent sessions") {
                    ForEach(viewModel.threads) { thread in
                        Button {
                            viewModel.resumeThread(thread)
                        } label: {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(thread.displayTitle)
                                    .foregroundStyle(Color.remotexText)
                                    .lineLimit(1)
                                if let cwd = thread.cwd, !cwd.isEmpty {
                                    Text(cwd)
                                        .font(.caption.monospaced())
                                        .foregroundStyle(Color.remotexMuted)
                                        .lineLimit(1)
                                        .truncationMode(.head)
                                }
                            }
                        }
                    }
                }
            }

        }
        .scrollContentBackground(.hidden)
        .background(Color.remotexBackground)
        .onAppear {
            if viewModel.hosts.isEmpty {
                viewModel.refreshHosts()
            }
        }
    }
}

private struct HostRow: View {
    let host: Host

    var body: some View {
        HStack(spacing: 12) {
            Circle()
                .fill(host.online ? Color.remotexGreen : Color.remotexMuted)
                .frame(width: 10, height: 10)
            VStack(alignment: .leading, spacing: 4) {
                Text(host.nickname)
                    .foregroundStyle(Color.remotexText)
                Text([host.hostname, host.platform].compactMap { $0 }.joined(separator: " / "))
                    .font(.caption)
                    .foregroundStyle(Color.remotexMuted)
                    .lineLimit(1)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color.remotexMuted)
        }
        .padding(.vertical, 4)
    }
}

private struct SessionView: View {
    @ObservedObject var viewModel: RemotexViewModel

    var body: some View {
        VStack(spacing: 0) {
            SessionHeader(viewModel: viewModel)
            Divider().overlay(Color.remotexLine)
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        if viewModel.historyHasMore {
                            HStack {
                                Spacer()
                                Text(viewModel.historyLoading ? "loading older turns…" : "older turns load as you scroll")
                                    .font(.system(size: 11, design: .monospaced))
                                    .foregroundStyle(Color.remotexMuted)
                                Spacer()
                            }
                            .onAppear { viewModel.loadOlderHistory() }
                            .id("history-loader")
                        }
                        ForEach(viewModel.stream) { item in
                            StreamRow(item: item)
                                .id(item.id)
                        }
                        if viewModel.stream.isEmpty {
                            EmptyStateRow(text: viewModel.status == .connected ? "send a prompt" : "connecting")
                                .padding()
                        }
                    }
                    .padding(12)
                }
                // Follow appends only: prepended history pages change the
                // HEAD of the stream, live output changes the TAIL. Keying
                // on the last id means backfill never yanks the view down.
                .onChange(of: viewModel.stream.last?.id) { _, lastId in
                    guard let lastId else { return }
                    withAnimation(.easeOut(duration: 0.2)) {
                        proxy.scrollTo(lastId, anchor: .bottom)
                    }
                }
                // A committed tail jumps to the newest exchange once.
                .onChange(of: viewModel.historyTailTick) { _, _ in
                    guard let last = viewModel.stream.last else { return }
                    proxy.scrollTo(last.id, anchor: .bottom)
                }
                // A committed older page restores the row that was at the
                // top before the prepend, so the view doesn't shift.
                .onChange(of: viewModel.historyChunkTick) { _, _ in
                    guard let anchor = viewModel.historyAnchorId else { return }
                    proxy.scrollTo(anchor, anchor: .top)
                }
            }
            PendingPromptsPanel(viewModel: viewModel)
            Composer(viewModel: viewModel)
        }
        .background(Color.remotexBackground)
    }
}

private struct SessionHeader: View {
    @ObservedObject var viewModel: RemotexViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(viewModel.selectedHost?.nickname ?? "Session")
                    .font(.headline)
                    .foregroundStyle(Color.remotexText)
                Spacer()
                StatusBadge(status: viewModel.status)
            }
            if let info = viewModel.session {
                Text([info.model, info.cwd].compactMap { $0 }.joined(separator: " / "))
                    .font(.caption)
                    .foregroundStyle(Color.remotexMuted)
                    .lineLimit(1)
            }
        }
        .padding(12)
        .background(Color.remotexSurface)
    }
}

private struct StatusBadge: View {
    let status: ConnectionStatus

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(color)
                .frame(width: 8, height: 8)
            Text(status.rawValue)
                .font(.caption.monospaced())
                .foregroundStyle(color)
        }
    }

    private var color: Color {
        switch status {
        case .connected:
            return .remotexGreen
        case .opening, .connecting, .loading:
            return .remotexAccent
        case .idle:
            return .remotexMuted
        case .disconnected, .error:
            return .remotexWarn
        }
    }
}

private struct StreamRow: View {
    let item: StreamItem

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Label(item.title, systemImage: icon)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(color)
                Spacer()
                if !item.completed && item.role != .user {
                    ProgressView()
                        .controlSize(.mini)
                }
            }
            if !item.detail.isEmpty {
                Text(item.detail)
                    .font(.caption.monospaced())
                    .foregroundStyle(Color.remotexMuted)
                    .textSelection(.enabled)
            }
            if !item.text.isEmpty {
                Text(item.text)
                    .font(.system(.body, design: item.role == .tool ? .monospaced : .default))
                    .foregroundStyle(Color.remotexText)
                    .textSelection(.enabled)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.remotexSurface)
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(Color.remotexLine, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private var icon: String {
        switch item.role {
        case .user:
            return "person.crop.circle"
        case .reasoning:
            return "brain.head.profile"
        case .tool:
            return "terminal"
        case .agent:
            return "sparkles"
        case .system:
            return "info.circle"
        case .gap:
            return "exclamationmark.triangle"
        }
    }

    private var color: Color {
        switch item.role {
        case .user:
            return .remotexAccent
        case .reasoning:
            return .remotexBlue
        case .tool:
            return .remotexGreen
        case .agent:
            return .remotexText
        case .system:
            return .remotexMuted
        case .gap:
            return .remotexWarn
        }
    }
}

private struct Composer: View {
    @ObservedObject var viewModel: RemotexViewModel

    private static let permissionOptions: [(id: String, label: String)] = [
        ("default", "Default"),
        ("full", "Full Access"),
        ("readonly", "Read Only"),
    ]

    private var effortOptions: [String] {
        let selected = viewModel.modelOptions.first { $0.id == viewModel.model }
        return selected?.efforts ?? ["", "low", "medium", "high", "xhigh"]
    }

    var body: some View {
      VStack(spacing: 8) {
        // Pre-turn settings, mirrored from the web chip row. Menus keep
        // the row one line tall; the values ride the next turn-start.
        if !viewModel.modelOptions.isEmpty {
            HStack(spacing: 8) {
                Menu {
                    ForEach(viewModel.modelOptions) { option in
                        Button(option.label) { viewModel.model = option.id }
                    }
                } label: {
                    PickerChip(
                        title: "MODEL",
                        value: viewModel.modelOptions.first { $0.id == viewModel.model }?.label ?? "default"
                    )
                }
                Menu {
                    ForEach(effortOptions, id: \.self) { option in
                        Button(option.isEmpty ? "default" : option) { viewModel.effort = option }
                    }
                } label: {
                    PickerChip(title: "EFFORT", value: viewModel.effort.isEmpty ? "default" : viewModel.effort)
                }
                Menu {
                    ForEach(Self.permissionOptions, id: \.id) { option in
                        Button(option.label) { viewModel.permissions = option.id }
                    }
                } label: {
                    PickerChip(
                        title: "PERMS",
                        value: Self.permissionOptions.first { $0.id == viewModel.permissions }?.label ?? "Default"
                    )
                }
            }
        }
        HStack(alignment: .bottom, spacing: 10) {
            TextField("Message Codex", text: $viewModel.prompt, axis: .vertical)
                .lineLimit(1...5)
                .textFieldStyle(.plain)
                .padding(10)
                .background(Color.remotexSurface)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(Color.remotexLine, lineWidth: 1)
                )
            if viewModel.pending {
                Button {
                    viewModel.interruptTurn()
                } label: {
                    Image(systemName: "stop.circle.fill")
                        .font(.system(size: 30))
                        .foregroundStyle(Color.remotexWarn)
                }
                .accessibilityLabel("Stop turn")
            }
            Button {
                viewModel.sendPrompt()
            } label: {
                // While a turn runs, typed text STEERS it (codex turn/steer)
                // instead of being locked out — same flow as web/Android.
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 30))
            }
            .disabled(
                viewModel.status != .connected
                    || viewModel.prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            )
            .accessibilityLabel(viewModel.pending ? "Steer turn" : "Send")
        }
      }
      .padding(12)
      .background(Color.remotexBackground)
    }
}

private struct PickerChip: View {
    let title: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.system(size: 9, design: .monospaced))
                .foregroundStyle(Color.remotexMuted)
            Text(value)
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(Color.remotexAccent)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(Color.remotexSurface)
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .stroke(Color.remotexLine, lineWidth: 1)
        )
    }
}

private struct EmptyStateRow: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption.monospaced())
            .foregroundStyle(Color.remotexMuted)
            .frame(maxWidth: .infinity, alignment: .center)
            .padding(.vertical, 20)
    }
}

#Preview {
    ContentView()
}

import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import UIKit

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var viewModel = RemotexViewModel()
    @StateObject private var theme = ThemeSetting()
    @State private var telemetryOpen = false
    @State private var filesOpen = false

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
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        theme.advance()
                    } label: {
                        Image(systemName: theme.iconName)
                    }
                    .accessibilityLabel("Theme: \(theme.choice.displayName)")
                }
                if viewModel.session != nil {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Files", systemImage: "folder") {
                            filesOpen = true
                        }
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Telemetry", systemImage: "speedometer") {
                            telemetryOpen = true
                        }
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Close") {
                            viewModel.closeSession()
                        }
                    }
                }
            }
            .sheet(isPresented: $telemetryOpen) {
                TelemetryView(viewModel: viewModel)
            }
            .sheet(isPresented: $filesOpen) {
                WorkspaceFilesView(viewModel: viewModel)
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
        .preferredColorScheme(theme.choice.colorScheme)
        // `colorSchemeContrast` is intentionally read-only: iOS owns the
        // system accessibility setting. The explicit in-app choice uses a
        // dark base plus a view contrast boost, while the dynamic palette
        // below still honors the system Increase Contrast setting as well.
        .contrast(theme.choice == .highContrast ? 1.35 : 1)
        .onChange(of: scenePhase) { _, phase in
            viewModel.setAppActive(phase != .background)
            if phase == .active {
                viewModel.reconnectAfterForeground()
                if viewModel.session == nil, viewModel.isConfigured {
                    viewModel.refreshHosts()
                }
            }
        }
    }
}

private struct HostsView: View {
    @ObservedObject var viewModel: RemotexViewModel
    @State private var startHost: Host?

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
                if !viewModel.relayURL.isEmpty,
                   RelayClient.validatedBaseComponents(baseURL: viewModel.relayURL) == nil {
                    Text(
                        RelayClient.allowsInsecureRelay
                            ? "Enter an HTTP or HTTPS relay URL without embedded credentials."
                            : "Use an HTTPS relay URL without embedded credentials."
                    )
                    .font(.caption)
                    .foregroundStyle(Color.remotexWarn)
                }
                Button {
                    viewModel.refreshHosts()
                } label: {
                    Label(
                        viewModel.status == .loading ? "Loading" : "Load hosts",
                        systemImage: "arrow.clockwise"
                    )
                }
                .disabled(!viewModel.isConfigured)
                if !viewModel.userToken.isEmpty {
                    Button(role: .destructive) {
                        viewModel.signOut()
                    } label: {
                        Label("Sign out", systemImage: "rectangle.portrait.and.arrow.right")
                    }
                }
            }

            Section("Hosts") {
                if viewModel.hosts.isEmpty {
                    EmptyStateRow(text: viewModel.status == .loading ? "loading" : "no hosts")
                } else {
                    ForEach(viewModel.hosts) { host in
                        Button {
                            startHost = host
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
        .refreshable {
            viewModel.refreshHosts()
        }
        .onAppear {
            if viewModel.hosts.isEmpty, viewModel.isConfigured {
                viewModel.refreshHosts()
            }
        }
        .sheet(item: $startHost) { host in
            HostStartView(viewModel: viewModel, host: host)
        }
    }
}

private struct HostStartView: View {
    @ObservedObject var viewModel: RemotexViewModel
    let host: Host
    @Environment(\.dismiss) private var dismiss
    @State private var typedPath = "/"

    var body: some View {
        NavigationStack {
            List {
                Section("Start a session") {
                    TextField("/path/on/host", text: $typedPath)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(.body, design: .monospaced))
                        .onSubmit {
                            viewModel.loadStartDirectory(on: host, path: typedPath)
                        }
                    HStack {
                        Button("Up", systemImage: "arrow.up") {
                            viewModel.loadStartDirectory(
                                on: host,
                                path: RemotexViewModel.parentRemotePath(viewModel.startPath)
                            )
                        }
                        .disabled(viewModel.startPath == "/")
                        Spacer()
                        Button("Go") {
                            viewModel.loadStartDirectory(on: host, path: typedPath)
                        }
                        Button("Start here") {
                            viewModel.openSession(host: host, cwd: viewModel.startPath)
                            dismiss()
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    Button("Start in host default folder") {
                        viewModel.openSession(host: host)
                        dismiss()
                    }
                }

                Section("Folders") {
                    if viewModel.startLoading && viewModel.startEntries.isEmpty {
                        ProgressView()
                    } else {
                        ForEach(viewModel.startEntries.filter(\.isDirectory)) { entry in
                            Button {
                                viewModel.loadStartDirectory(
                                    on: host,
                                    path: RemotexViewModel.joinRemotePath(
                                        viewModel.startPath,
                                        entry.fileName
                                    )
                                )
                            } label: {
                                Label(entry.fileName, systemImage: "folder")
                                    .font(.system(.body, design: .monospaced))
                            }
                        }
                    }
                }

                if viewModel.threadsHost?.id == host.id, !viewModel.threads.isEmpty {
                    Section("Saved chats") {
                        ForEach(viewModel.threads) { thread in
                            Button {
                                viewModel.resumeThread(thread)
                                dismiss()
                            } label: {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(thread.displayTitle).lineLimit(1)
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
            .navigationTitle(host.nickname)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Refresh", systemImage: "arrow.clockwise") {
                        viewModel.refreshHostExtras(host)
                        viewModel.loadStartDirectory(on: host, path: viewModel.startPath)
                    }
                }
            }
        }
        .onAppear {
            viewModel.prepareStart(on: host)
        }
        .onChange(of: viewModel.startPath) { _, path in
            typedPath = path
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
                            StreamRow(
                                item: item,
                                streaming: viewModel.pending && !item.completed
                            )
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
                .overlay(alignment: .bottomTrailing) {
                    // Jump-to-latest, mirroring the web pill. Shown whenever
                    // there's a transcript to jump within; a live dot marks a
                    // running turn.
                    if viewModel.stream.count > 3 {
                        Button {
                            guard let last = viewModel.stream.last else { return }
                            withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                        } label: {
                            Image(systemName: "arrow.down")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(Color.remotexText)
                                .frame(width: 40, height: 40)
                                .background(Color.remotexSurface)
                                .clipShape(Circle())
                                .overlay(Circle().stroke(Color.remotexLine, lineWidth: 1))
                                .overlay(alignment: .topTrailing) {
                                    if viewModel.pending {
                                        Circle()
                                            .fill(Color.remotexGreen)
                                            .frame(width: 7, height: 7)
                                            .offset(x: -4, y: 4)
                                    }
                                }
                        }
                        .buttonStyle(.plain)
                        .padding(14)
                        .accessibilityLabel("Jump to latest")
                    }
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
            if let message = viewModel.connectionMessage, !message.isEmpty {
                Text(message)
                    .font(.caption)
                    .foregroundStyle(Color.remotexMuted)
                    .lineLimit(2)
            }
            if let goal = viewModel.goal {
                HStack(spacing: 8) {
                    Text("goal \(goal.status.isEmpty ? "active" : goal.status)")
                    if let budget = goal.tokenBudget, budget > 0 {
                        ProgressView(
                            value: Double(min(goal.tokensUsed, budget)),
                            total: Double(budget)
                        )
                        .frame(maxWidth: 120)
                        Text("\(compactCount(goal.tokensUsed))/\(compactCount(budget))")
                    } else if goal.tokensUsed > 0 {
                        Text("\(compactCount(goal.tokensUsed)) used")
                    }
                }
                .font(.caption.monospaced())
                .foregroundStyle(Color.remotexMuted)
                if !goal.objective.isEmpty {
                    Text(goal.objective)
                        .font(.caption)
                        .foregroundStyle(Color.remotexMuted)
                        .lineLimit(1)
                }
            }
            if [
                viewModel.tokensInput,
                viewModel.tokensOutput,
                viewModel.tokensCached,
                viewModel.tokensReasoning,
            ].contains(where: { $0 > 0 }) {
                Text(
                    "tokens \(compactCount(viewModel.tokensInput)) in · "
                        + "\(compactCount(viewModel.tokensOutput)) out"
                )
                .font(.caption.monospaced())
                .foregroundStyle(Color.remotexMuted)
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

// Transcript rows the way Claude Code / Codex present them:
//   user       → right-aligned bubble
//   reasoning  → "✳ Thinking" live, folding to one dim headline when done
//   tool       → "● name(arg)" header + ⎿ output block (diff card for edits)
//   agent      → markdown prose
private struct StreamRow: View {
    let item: StreamItem
    let streaming: Bool

    var body: some View {
        switch item.role {
        case .user:
            UserBubble(item: item)
        case .reasoning:
            ThinkingRow(item: item, streaming: streaming)
        case .tool:
            ToolRow(item: item, streaming: streaming)
        case .agent:
            VStack(alignment: .leading, spacing: 4) {
                Text("CODEX")
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(Color.remotexAccent)
                MarkdownText(text: item.text)
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.remotexSurface)
            .clipShape(RoundedRectangle(cornerRadius: 8))
        case .gap:
            HStack {
                Spacer()
                Text(item.text.isEmpty ? item.title : item.text)
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(Color.remotexWarn)
                Spacer()
            }
        case .system:
            VStack(alignment: .leading, spacing: 3) {
                Text(item.title.uppercased())
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(Color.remotexMuted)
                if !item.text.isEmpty {
                    Text(item.text)
                        .font(.system(size: 12))
                        .foregroundStyle(Color.remotexMuted)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

private struct UserBubble: View {
    let item: StreamItem

    var body: some View {
        HStack {
            Spacer(minLength: 24)
            VStack(alignment: .leading, spacing: 4) {
                Text("YOU")
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(Color.remotexBlue)
                    .frame(maxWidth: .infinity, alignment: .trailing)
                Text(item.text)
                    .font(.system(size: 14))
                    .foregroundStyle(Color.remotexText)
                    .textSelection(.enabled)
                if item.imageCount > 0 {
                    Label(
                        "\(item.imageCount) image\(item.imageCount == 1 ? "" : "s")",
                        systemImage: "photo"
                    )
                    .font(.caption.monospaced())
                    .foregroundStyle(Color.remotexMuted)
                }
                if !item.imageData.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(Array(item.imageData.enumerated()), id: \.offset) { _, data in
                                if let preview = UIImage(data: data) {
                                    Image(uiImage: preview)
                                        .resizable()
                                        .scaledToFill()
                                        .frame(width: 96, height: 72)
                                        .clipShape(RoundedRectangle(cornerRadius: 6))
                                }
                            }
                        }
                    }
                }
            }
            .padding(10)
            .background(Color.remotexAccent.opacity(0.10))
            .overlay(alignment: .trailing) {
                Rectangle().fill(Color.remotexBlue).frame(width: 2)
            }
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
    }
}

private func compactCount(_ value: Int) -> String {
    if value >= 1_000_000 {
        return String(format: value >= 10_000_000 ? "%.0fm" : "%.1fm", Double(value) / 1_000_000)
    }
    if value >= 1_000 {
        return String(format: value >= 10_000 ? "%.0fk" : "%.1fk", Double(value) / 1_000)
    }
    return String(max(0, value))
}

private struct ThinkingRow: View {
    let item: StreamItem
    let streaming: Bool
    @State private var open = false

    private var expanded: Bool { streaming || open }

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Button {
                if !streaming { open.toggle() }
            } label: {
                HStack(spacing: 6) {
                    Text("✳").foregroundStyle(Color.remotexAccentDeep)
                    Text(streaming ? "Thinking…" : (open ? "Thought" : "Thought · \(headline(item.text))"))
                        .foregroundStyle(Color.remotexMuted)
                        .lineLimit(1)
                }
                .font(.system(size: 11, design: .monospaced))
            }
            .buttonStyle(.plain)
            if expanded {
                MarkdownText(text: item.text.isEmpty ? "…" : item.text, color: .remotexMuted, fontSize: 12)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct ToolRow: View {
    let item: StreamItem
    let streaming: Bool
    @State private var expanded = false

    private var isEdit: Bool { item.title == "edit" }
    private var lines: [String] { item.text.components(separatedBy: "\n") }
    private var limit: Int { streaming ? 4 : 5 }
    private var overflow: Bool { lines.count > limit }
    private var shown: String {
        if expanded || !overflow { return item.text }
        // Running: follow the tail like a terminal.
        return streaming
            ? lines.suffix(limit).joined(separator: "\n")
            : lines.prefix(limit - 1).joined(separator: "\n")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Button { expanded.toggle() } label: {
                HStack(spacing: 7) {
                    Circle()
                        .fill(streaming ? Color.remotexAccent : Color.remotexGreen)
                        .frame(width: 8, height: 8)
                    Text(item.title)
                        .font(.system(size: 12, design: .monospaced).weight(.semibold))
                        .foregroundStyle(Color.remotexText)
                    if !isEdit, !item.detail.isEmpty {
                        Text("(\(headline(item.detail)))")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(Color.remotexMuted)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 0)
                }
            }
            .buttonStyle(.plain)

            if isEdit, !item.text.isEmpty {
                DiffView(summary: item.detail, diff: item.text, streaming: streaming)
            } else if !item.text.isEmpty {
                HStack(alignment: .top, spacing: 6) {
                    Text("⎿")
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(Color.remotexMuted)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(shown)
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(Color.remotexMuted)
                            .textSelection(.enabled)
                        if overflow, !expanded {
                            Button("… \(lines.count - limit + 1) more lines") { expanded = true }
                                .font(.system(size: 10, design: .monospaced))
                                .foregroundStyle(Color.remotexAccent)
                        }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// First line, markdown emphasis stripped, clipped for a header row.
private func headline(_ text: String) -> String {
    let raw = text.components(separatedBy: "\n").first?
        .trimmingCharacters(in: .whitespaces)
        .replacingOccurrences(of: "**", with: "")
        .replacingOccurrences(of: "__", with: "") ?? ""
    return raw.count > 80 ? String(raw.prefix(77)) + "…" : raw
}

private struct Composer: View {
    @ObservedObject var viewModel: RemotexViewModel
    @State private var pickedPhotos: [PhotosPickerItem] = []

    private static let permissionOptions: [(id: String, label: String)] = [
        ("default", "Default"),
        ("full", "Full Access"),
        ("readonly", "Read Only"),
    ]

    private var effortOptions: [String] {
        let selected = viewModel.modelOptions.first { $0.id == viewModel.model }
        return selected?.efforts ?? ["", "low", "medium", "high", "xhigh"]
    }

    private var connected: Bool { viewModel.status == .connected }

    private var slashOnly: Bool {
        viewModel.pendingImages.isEmpty
            && viewModel.prompt.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("/")
    }

    var body: some View {
        VStack(spacing: 8) {
            if !viewModel.pendingImages.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(viewModel.pendingImages) { image in
                            PendingImageTile(image: image) {
                                viewModel.removeImage(image.id)
                            }
                        }
                    }
                }
            }

            if !viewModel.queuedTurns.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        Text("NEXT (\(viewModel.queuedTurns.count))")
                            .font(.system(size: 9, design: .monospaced))
                            .foregroundStyle(Color.remotexMuted)
                        ForEach(viewModel.queuedTurns) { turn in
                            HStack(spacing: 5) {
                                Text(turn.text.ifEmpty("\(turn.images.count) image\(turn.images.count == 1 ? "" : "s")"))
                                    .lineLimit(1)
                                Button {
                                    viewModel.removeQueuedTurn(turn.id)
                                } label: {
                                    Image(systemName: "xmark")
                                }
                                .disabled(turn.sending)
                            }
                            .font(.caption.monospaced())
                            .padding(.horizontal, 8)
                            .padding(.vertical, 5)
                            .background(Color.remotexSurface)
                            .clipShape(RoundedRectangle(cornerRadius: 6))
                        }
                    }
                }
            }

            // Settings and control commands stay one line tall on phones.
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                Menu {
                    Button("Codex default") { viewModel.model = "" }
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
                Menu {
                    Button(viewModel.planMode ? "Use default mode" : "Use plan mode") {
                        viewModel.sendSlash(viewModel.planMode ? "default" : "plan")
                    }
                    Button("List collaboration modes") {
                        viewModel.sendSlash("collab")
                    }
                    ForEach(viewModel.collaborationModes, id: \.self) { mode in
                        Button(mode, action: {})
                            .disabled(true)
                    }
                } label: {
                    PickerChip(title: "MODE", value: viewModel.planMode ? "plan" : "default")
                }
                Menu {
                    Button("Inspect goal") { viewModel.refreshGoal() }
                    Button("Set or update goal") { beginGoalComposition() }
                    if let goal = viewModel.goal {
                        if goal.status == "paused" {
                            Button("Resume goal") { viewModel.resumeGoal() }
                        } else {
                            Button("Pause goal") { viewModel.pauseGoal() }
                        }
                        Button("Clear goal", role: .destructive) { viewModel.clearGoal() }
                    }
                } label: {
                    PickerChip(title: "GOAL", value: viewModel.goal?.status ?? "/goal")
                }
                Menu {
                    Button("/plan") { viewModel.sendSlash("plan") }
                    Button("/default") { viewModel.sendSlash("default") }
                    Button("/goal …") { beginGoalComposition() }
                    Button("/cd …") { viewModel.prompt = "/cd " }
                    Button("/pwd") { viewModel.sendSlash("pwd") }
                    Button("/compact") { viewModel.sendSlash("compact") }
                    Button("/collab") { viewModel.sendSlash("collab") }
                } label: {
                    PickerChip(title: "CMD", value: "/")
                }
            }
            }

            HStack(alignment: .bottom, spacing: 8) {
                PhotosPicker(
                    selection: $pickedPhotos,
                    maxSelectionCount: 5,
                    matching: .images
                ) {
                    Image(systemName: "paperclip")
                        .frame(width: 30, height: 30)
                }
                .disabled(!connected)
                .accessibilityLabel("Attach image")

                TextField(
                    viewModel.pending ? "Steer this turn" : "Message Codex",
                    text: $viewModel.prompt,
                    axis: .vertical
                )
                    .lineLimit(1...5)
                    .textFieldStyle(.plain)
                    .padding(10)
                    .background(Color.remotexSurface)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(Color.remotexLine, lineWidth: 1)
                    )
                    .disabled(!connected)

                if viewModel.pending {
                Button {
                    viewModel.interruptTurn()
                } label: {
                    Image(systemName: "stop.circle.fill")
                        .font(.system(size: 30))
                        .foregroundStyle(Color.remotexWarn)
                }
                .accessibilityLabel("Stop turn")
                    if viewModel.hasComposerContent, !slashOnly {
                        Button {
                            viewModel.queuePrompt()
                        } label: {
                            Image(systemName: "tray.and.arrow.down.fill")
                                .font(.system(size: 24))
                        }
                        .accessibilityLabel("Queue follow-up")
                    }
                }
                Button {
                    viewModel.sendPrompt()
                } label: {
                    // While a turn runs, typed text STEERS it. The adjacent
                    // tray button queues a FIFO follow-up instead.
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: 30))
                }
                .disabled(!connected || !viewModel.hasComposerContent)
                .accessibilityLabel(viewModel.pending ? "Steer turn" : "Send")
            }
        }
        .padding(12)
        .background(Color.remotexBackground)
        .onChange(of: pickedPhotos) { _, items in
            loadPhotos(items)
        }
    }

    private func loadPhotos(_ items: [PhotosPickerItem]) {
        Task { @MainActor in
            for item in items {
                do {
                    guard let data = try await item.loadTransferable(type: Data.self) else {
                        throw CocoaError(.fileReadCorruptFile)
                    }
                    let type = item.supportedContentTypes.first { $0.conforms(to: .image) }
                    let mime = type?.preferredMIMEType ?? "image/jpeg"
                    let label = type?.preferredFilenameExtension.map { "photo.\($0)" } ?? "photo"
                    viewModel.attachImage(data: data, mime: mime, label: label)
                } catch {
                    viewModel.errorMessage = "Image: \(error.localizedDescription)"
                }
            }
            pickedPhotos = []
        }
    }

    private func beginGoalComposition() {
        if viewModel.planMode {
            viewModel.sendSlash("default")
        }
        viewModel.prompt = "/goal "
    }
}

private struct PendingImageTile: View {
    let image: PendingImage
    let onRemove: () -> Void

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Group {
                if let preview = UIImage(data: image.data) {
                    Image(uiImage: preview)
                        .resizable()
                        .scaledToFill()
                } else {
                    Image(systemName: "photo")
                        .foregroundStyle(Color.remotexMuted)
                }
            }
            .frame(width: 56, height: 56)
            .background(Color.remotexSurface)
            .clipShape(RoundedRectangle(cornerRadius: 6))

            Button(action: onRemove) {
                Image(systemName: "xmark.circle.fill")
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(.white, .black.opacity(0.75))
            }
            .offset(x: 5, y: -5)
            .accessibilityLabel("Remove \(image.label)")
        }
        .padding(.top, 5)
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
        .frame(minWidth: 92, alignment: .leading)
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

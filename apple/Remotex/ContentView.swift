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

            Section("Search") {
                TextField("Search chats", text: $viewModel.searchQuery)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.system(.body, design: .monospaced))
                    .onSubmit {
                        viewModel.searchChats()
                    }
                Button {
                    viewModel.searchChats()
                } label: {
                    Label(
                        viewModel.searchLoading ? "Searching" : "Search chats",
                        systemImage: "magnifyingglass"
                    )
                }
                if viewModel.searchResults.isEmpty {
                    EmptyStateRow(text: viewModel.searchLoading ? "searching" : "no search results")
                } else {
                    ForEach(viewModel.searchResults) { result in
                        Button {
                            viewModel.openSearchResult(result)
                        } label: {
                            SearchResultRow(result: result)
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

private struct SearchResultRow: View {
    let result: SearchResult

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(result.snippet.isEmpty ? result.text : result.snippet)
                .foregroundStyle(Color.remotexText)
                .lineLimit(4)
            HStack(spacing: 8) {
                Text(result.role)
                    .foregroundStyle(Color.remotexAccent)
                Text("\(Int(result.score * 100))%")
                if let cwd = result.cwd, !cwd.isEmpty {
                    Text(cwd)
                        .lineLimit(1)
                }
            }
            .font(.caption.monospaced())
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
                .onChange(of: viewModel.stream) { _, items in
                    guard let last = items.last else { return }
                    withAnimation(.easeOut(duration: 0.2)) {
                        proxy.scrollTo(last.id, anchor: .bottom)
                    }
                }
            }
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
        }
    }
}

private struct Composer: View {
    @ObservedObject var viewModel: RemotexViewModel

    var body: some View {
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
            Button {
                viewModel.sendPrompt()
            } label: {
                Image(systemName: viewModel.pending ? "hourglass" : "arrow.up.circle.fill")
                    .font(.system(size: 30))
            }
            .disabled(viewModel.status != .connected || viewModel.prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .padding(12)
        .background(Color.remotexBackground)
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

import SwiftUI

// Browse the session's working directory, matching the web drawer and the
// Android panel: folders first (dotfiles last within each group), tap a
// folder to descend, ↑ to go up.
//
// Read-only for now — rename/delete/download need the fs mutation
// endpoints wired through RelayClient, and a destructive action is not
// something to ship untested on a client nobody has run yet.
struct WorkspaceFilesView: View {
    @ObservedObject var viewModel: RemotexViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack(spacing: 8) {
                    Button {
                        let parent = parentPath(viewModel.workspacePath)
                        viewModel.loadWorkspace(parent)
                    } label: {
                        Image(systemName: "arrow.up")
                            .frame(width: 34, height: 34)
                    }
                    .buttonStyle(.plain)
                    .disabled(viewModel.workspacePath == "/" || viewModel.workspacePath.isEmpty)
                    Text(viewModel.workspacePath.ifEmpty(viewModel.session?.cwd ?? "/"))
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(Color.remotexMuted)
                        .lineLimit(1)
                        .truncationMode(.head)
                    Spacer()
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color.remotexSurface2)

                if viewModel.workspaceLoading && viewModel.workspaceEntries.isEmpty {
                    Spacer()
                    Text("loading…")
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(Color.remotexMuted)
                    Spacer()
                } else if viewModel.workspaceEntries.isEmpty {
                    Spacer()
                    Text("empty")
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(Color.remotexMuted)
                    Spacer()
                } else {
                    List(viewModel.workspaceEntries) { entry in
                        Button {
                            guard entry.isDirectory else { return }
                            viewModel.loadWorkspace(
                                join(viewModel.workspacePath, entry.fileName)
                            )
                        } label: {
                            HStack(spacing: 8) {
                                Text(entry.isDirectory ? "▸" : "▪")
                                    .foregroundStyle(
                                        entry.isDirectory ? Color.remotexAccent : Color.remotexMuted
                                    )
                                    .frame(width: 14)
                                Text(entry.fileName + (entry.isDirectory ? "/" : ""))
                                    .font(.system(size: 13, design: .monospaced))
                                    .foregroundStyle(
                                        entry.isDirectory ? Color.remotexAccent : Color.remotexText
                                    )
                                    .lineLimit(1)
                                Spacer()
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .listRowBackground(Color.remotexBackground)
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                }
            }
            .background(Color.remotexBackground)
            .navigationTitle("Workspace files")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .onAppear { viewModel.loadWorkspace() }
    }
}

private func parentPath(_ path: String) -> String {
    guard path != "/", !path.isEmpty else { return "/" }
    let trimmed = path.hasSuffix("/") ? String(path.dropLast()) : path
    guard let slash = trimmed.lastIndex(of: "/") else { return "/" }
    let parent = String(trimmed[..<slash])
    return parent.isEmpty ? "/" : parent
}

private func join(_ base: String, _ name: String) -> String {
    let root = base.ifEmpty("/")
    return root.hasSuffix("/") ? root + name : "\(root)/\(name)"
}

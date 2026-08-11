import SwiftUI
import UniformTypeIdentifiers
import UIKit

struct WorkspaceFilesView: View {
    @ObservedObject var viewModel: RemotexViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var renameTarget: FsEntry?
    @State private var renameValue = ""
    @State private var deleteTarget: FsEntry?
    @State private var newFolderOpen = false
    @State private var newFolderName = ""
    @State private var fileImporterOpen = false
    @State private var sharedFileURL: URL?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack(spacing: 8) {
                    Button {
                        viewModel.loadWorkspace(
                            RemotexViewModel.parentRemotePath(viewModel.workspacePath)
                        )
                    } label: {
                        Image(systemName: "arrow.up")
                            .frame(width: 44, height: 44)
                    }
                    .buttonStyle(.plain)
                    .disabled(viewModel.workspacePath == "/" || viewModel.workspacePath.isEmpty)
                    Text(viewModel.workspacePath.ifEmpty(viewModel.session?.cwd ?? "/"))
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(Color.remotexMuted)
                        .lineLimit(1)
                        .truncationMode(.head)
                    Spacer()
                    if viewModel.workspaceLoading {
                        ProgressView().controlSize(.small)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 4)
                .background(Color.remotexSurface2)

                if let error = viewModel.errorMessage {
                    HStack(alignment: .top, spacing: 8) {
                        Text(error)
                            .font(.caption)
                            .foregroundStyle(Color.remotexWarn)
                        Spacer()
                        Button("Dismiss", systemImage: "xmark") {
                            viewModel.errorMessage = nil
                        }
                        .labelStyle(.iconOnly)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Color.remotexWarn.opacity(0.08))
                }

                if viewModel.workspaceLoading && viewModel.workspaceEntries.isEmpty {
                    Spacer()
                    ProgressView("Loading…")
                    Spacer()
                } else if viewModel.workspaceEntries.isEmpty {
                    Spacer()
                    ContentUnavailableView(
                        "Empty folder",
                        systemImage: "folder",
                        description: Text("Upload a file or create a folder here.")
                    )
                    Spacer()
                } else {
                    List(viewModel.workspaceEntries) { entry in
                        HStack(spacing: 8) {
                            Button {
                                open(entry)
                            } label: {
                                HStack(spacing: 8) {
                                    Image(systemName: entry.isDirectory ? "folder" : "doc")
                                        .foregroundStyle(
                                            entry.isDirectory ? Color.remotexAccent : Color.remotexMuted
                                        )
                                        .frame(width: 18)
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

                            Menu {
                                if !entry.isDirectory {
                                    Button("Share or save", systemImage: "square.and.arrow.up") {
                                        open(entry)
                                    }
                                }
                                Button("Rename", systemImage: "pencil") {
                                    renameValue = entry.fileName
                                    renameTarget = entry
                                }
                                if !entry.isDirectory {
                                    Button("Delete", systemImage: "trash", role: .destructive) {
                                        deleteTarget = entry
                                    }
                                }
                            } label: {
                                Image(systemName: "ellipsis")
                                    .frame(width: 44, height: 44)
                            }
                            .accessibilityLabel("Actions for \(entry.fileName)")
                        }
                        .disabled(viewModel.workspaceLoading)
                        .listRowBackground(Color.remotexBackground)
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                    .refreshable { viewModel.loadWorkspace(viewModel.workspacePath) }
                }
            }
            .background(Color.remotexBackground)
            .navigationTitle("Workspace files")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Menu {
                        Button("Upload file", systemImage: "square.and.arrow.up") {
                            fileImporterOpen = true
                        }
                        Button("New folder", systemImage: "folder.badge.plus") {
                            newFolderName = ""
                            newFolderOpen = true
                        }
                        Button("Refresh", systemImage: "arrow.clockwise") {
                            viewModel.loadWorkspace(viewModel.workspacePath)
                        }
                    } label: {
                        Label("Add", systemImage: "plus")
                    }
                    .disabled(viewModel.workspaceLoading)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .onAppear { viewModel.loadWorkspace() }
        .fileImporter(
            isPresented: $fileImporterOpen,
            allowedContentTypes: [.data],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case let .success(urls):
                if let url = urls.first { viewModel.uploadWorkspaceFile(from: url) }
            case let .failure(error):
                viewModel.errorMessage = error.localizedDescription
            }
        }
        .alert("New folder", isPresented: $newFolderOpen) {
            TextField("Folder name", text: $newFolderName)
            Button("Create") { viewModel.createWorkspaceDirectory(newFolderName) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Create a folder inside \(viewModel.workspacePath.ifEmpty("/"))")
        }
        .alert(
            "Rename \(renameTarget?.fileName ?? "item")",
            isPresented: Binding(
                get: { renameTarget != nil },
                set: { if !$0 { renameTarget = nil } }
            )
        ) {
            TextField("New name", text: $renameValue)
            Button("Rename") {
                if let target = renameTarget {
                    viewModel.renameWorkspaceEntry(target, to: renameValue)
                }
                renameTarget = nil
            }
            Button("Cancel", role: .cancel) { renameTarget = nil }
        } message: {
            Text("The destination must stay in the current folder.")
        }
        .confirmationDialog(
            "Delete \(deleteTarget?.fileName ?? "file")?",
            isPresented: Binding(
                get: { deleteTarget != nil },
                set: { if !$0 { deleteTarget = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Delete permanently", role: .destructive) {
                if let target = deleteTarget { viewModel.deleteWorkspaceFile(target) }
                deleteTarget = nil
            }
            Button("Cancel", role: .cancel) { deleteTarget = nil }
        } message: {
            Text("This cannot be undone. Folders are never deleted from the app.")
        }
        .sheet(
            isPresented: Binding(
                get: { sharedFileURL != nil },
                set: { presented in
                    if !presented {
                        cleanupSharedFile()
                        sharedFileURL = nil
                    }
                }
            )
        ) {
            if let url = sharedFileURL {
                ActivityView(items: [url])
                    .presentationDetents([.medium, .large])
            }
        }
        .onDisappear { cleanupSharedFile() }
    }

    private func open(_ entry: FsEntry) {
        if entry.isDirectory {
            viewModel.loadWorkspace(
                RemotexViewModel.joinRemotePath(viewModel.workspacePath, entry.fileName)
            )
            return
        }
        Task {
            cleanupSharedFile()
            sharedFileURL = await viewModel.downloadWorkspaceFile(entry)
        }
    }

    private func cleanupSharedFile() {
        guard let sharedFileURL else { return }
        try? FileManager.default.removeItem(at: sharedFileURL.deletingLastPathComponent())
    }
}

private struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(
        _ uiViewController: UIActivityViewController,
        context: Context
    ) {}
}

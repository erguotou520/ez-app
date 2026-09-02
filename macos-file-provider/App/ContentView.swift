import SwiftUI

struct ContentView: View {
    @EnvironmentObject var model: AppModel
    @State private var adding = false
    @State private var editingSource: DataSource?

    var body: some View {
        NavigationSplitView {
            VStack(spacing: 0) {
                HStack {
                    Text("存储源").font(.headline)
                    Spacer()
                    Button { adding = true } label: {
                        Image(systemName: "plus")
                    }
                    .buttonStyle(.borderless)
                    .help("添加存储源")
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                Divider()
                List(selection: $model.selectedSourceID) {
                    ForEach(model.sources) { source in
                        Label(source.name, systemImage: icon(source.kind)).tag(source.id)
                            .contextMenu {
                                Button("修改") { editingSource = source }
                                Divider()
                                Button("移除", role: .destructive) {
                                    Task { await model.remove(source) }
                                }
                            }
                    }
                    Section {
                        Label("待同步变更 \(model.changes.count)", systemImage: "arrow.triangle.2.circlepath")
                    }
                }
            }
        } content: {
            if model.sources.isEmpty {
                VStack(spacing: 14) {
                    Image(systemName: "externaldrive.badge.plus")
                        .font(.system(size: 42))
                        .foregroundStyle(.secondary)
                    Text("还没有存储源").font(.title2.bold())
                    Text("添加 SMB、WebDAV、S3 或 SFTP 存储源")
                        .foregroundStyle(.secondary)
                    Button("添加存储源") { adding = true }
                        .buttonStyle(.borderedProminent)
                }
            } else {
                FileBrowserView()
            }
        } detail: {
            ChangesView()
        }
        .sheet(isPresented: $adding) { SourceFormView() }
        .sheet(item: $editingSource) { SourceFormView(source: $0) }
        .task { await model.load() }
        .onChange(of: model.selectedSourceID) { _ in Task { await model.refresh(path: "/") } }
        .frame(minWidth: 1280, minHeight: 720)
    }

    private func icon(_ kind: SourceKind) -> String {
        switch kind {
        case .webDAV: "globe"
        case .s3: "shippingbox"
        case .sftp: "terminal"
        case .smb: "externaldrive.connected.to.line.below"
        }
    }
}

private struct FileBrowserView: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button { Task { await model.refresh(path: (model.currentPath as NSString).deletingLastPathComponent) } }
                    label: { Image(systemName: "chevron.left") }.disabled(model.currentPath == "/")
                Text(model.currentPath).foregroundStyle(.secondary).lineLimit(1)
                Spacer()
                Button("清理本地下载") { Task { await model.clearDownloaded() } }
                Button { Task { await model.refresh() } } label: { Image(systemName: "arrow.clockwise") }
            }.padding(10)
            Divider()
            Table(model.items) {
                TableColumn("名称") { item in
                    Label(item.name, systemImage: item.isDirectory ? "folder.fill" : "doc")
                        .onTapGesture(count: 2) { Task { await model.open(item) } }
                        .contextMenu {
                            Button(item.isDirectory ? "打开" : "下载并打开") { Task { await model.open(item) } }
                        }
                }
                .width(min: 360, ideal: 520)
                TableColumn("大小") { item in Text(item.isDirectory ? "—" : ByteCountFormatter.string(fromByteCount: item.size, countStyle: .file)) }
                    .width(min: 100, ideal: 130)
                TableColumn("状态") { _ in Text("仅在线").foregroundStyle(.secondary) }
                    .width(min: 90, ideal: 110)
            }
            if !model.message.isEmpty { Text(model.message).foregroundStyle(.orange).padding(8) }
        }
    }
}

private struct ChangesView: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("同步审核").font(.title2.bold())
            Text("Finder 中的修改会先暂存在这里。只有勾选的项目会写回远端。").foregroundStyle(.secondary)
            List {
                ForEach($model.changes) { $change in
                    Toggle(isOn: $change.isSelected) {
                        VStack(alignment: .leading) {
                            Text(change.path).lineLimit(1)
                            Text(change.kind.rawValue).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            Toggle("同步删除远端文件", isOn: $model.syncRemoteDeletes)
            Button("同步所选") { Task { await model.syncSelected() } }
                .buttonStyle(.borderedProminent)
                .disabled(!model.changes.contains(where: \.isSelected))
        }.padding()
    }
}

private struct SourceFormView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    private let original: DataSource?
    @State private var kind: SourceKind
    @State private var name: String
    @State private var endpoint: String
    @State private var root: String
    @State private var username: String
    @State private var secret = ""
    @State private var bucket: String
    @State private var region: String

    init(source: DataSource? = nil) {
        original = source
        _kind = State(initialValue: source?.kind ?? .webDAV)
        _name = State(initialValue: source?.name ?? "")
        _endpoint = State(initialValue: source?.endpoint ?? "")
        _root = State(initialValue: source?.rootPath ?? "/")
        _username = State(initialValue: source?.username ?? "")
        _bucket = State(initialValue: source?.bucket ?? "")
        _region = State(initialValue: source?.region ?? "us-east-1")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(original == nil ? "添加存储源" : "修改存储源").font(.title2.bold())
            Picker("类型", selection: $kind) { ForEach(SourceKind.allCases) { Text($0.title).tag($0) } }
                .pickerStyle(.segmented)
            Form {
                TextField("显示名称", text: $name)
                if let nameValidationMessage {
                    Text(nameValidationMessage)
                        .font(.caption)
                        .foregroundStyle(.red)
                }
                LabeledContent("地址") {
                    TextField("", text: $endpoint, prompt: Text(endpointPlaceholder))
                }
                TextField("根目录", text: $root)
                TextField(kind == .s3 ? "Access Key" : "用户名", text: $username)
                SecureField(secretLabel, text: $secret)
                if kind == .s3 {
                    TextField("Bucket", text: $bucket)
                    TextField("Region", text: $region)
                }
            }
            HStack {
                Spacer()
                Button("取消") { dismiss() }
                Button(original == nil ? "添加并挂载" : "保存修改") {
                    let id = original?.id ?? UUID()
                    let source = DataSource(id: id, name: normalizedName, kind: kind, endpoint: endpoint,
                                            rootPath: root, username: username, bucket: bucket,
                                            region: region,
                                            credentialAccount: original?.credentialAccount ?? id.uuidString,
                                            isMounted: original?.isMounted ?? true)
                    Task {
                        let saved = if original == nil {
                            await model.add(source, secret: secret)
                        } else {
                            await model.update(source, secret: secret)
                        }
                        if saved {
                            dismiss()
                        }
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(nameValidationMessage != nil || endpoint.isEmpty)
            }
        }.padding(24).frame(width: 520)
    }

    private var endpointPlaceholder: String {
        switch kind {
        case .webDAV: "https://server.example/dav"
        case .s3: "https://s3.example.com"
        case .sftp: "sftp://host:22"
        case .smb: "smb://host/share"
        }
    }

    private var secretLabel: String {
        if original != nil {
            return kind == .s3 ? "Secret Key（留空则不修改）" : "密码/口令（留空则不修改）"
        }
        return kind == .s3 ? "Secret Key" : "密码/口令"
    }

    private var normalizedName: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var nameValidationMessage: String? {
        if let message = DisplayNameRules.validationMessage(name) {
            return message
        }
        let key = DisplayNameRules.comparisonKey(name)
        if model.sources.contains(where: {
            $0.id != original?.id && DisplayNameRules.comparisonKey($0.name) == key
        }) {
            return "显示名称已存在，请换一个名称"
        }
        return nil
    }
}

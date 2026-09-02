import AppKit
import Foundation

private enum LauncherResult {
    case calculation(String)
    case application(name: String, url: URL, icon: NSImage)
    case translation(String)
    case message(String)

    var title: String {
        switch self {
        case .calculation(let value), .translation(let value), .message(let value): value
        case .application(let name, _, _): name
        }
    }

    var subtitle: String {
        switch self {
        case .calculation: "计算结果"
        case .application: "应用程序"
        case .translation: "翻译结果"
        case .message: ""
        }
    }

    var icon: NSImage? {
        switch self {
        case .calculation:
            NSImage(systemSymbolName: "function", accessibilityDescription: "计算")
        case .translation:
            NSImage(systemSymbolName: "character.book.closed", accessibilityDescription: "翻译")
        case .application(_, _, let icon): icon
        case .message:
            NSImage(systemSymbolName: "info.circle", accessibilityDescription: "提示")
        }
    }
}

private struct InstalledApplication {
    let name: String
    let searchText: String
    let url: URL
    let icon: NSImage
}

final class CommandDoubleTapMonitor {
    private var globalMonitor: Any?
    private var localMonitor: Any?
    private var lastCommandDown: TimeInterval = 0
    private var lastHandledAt: TimeInterval = 0
    private var started = false
    private var eventTap: CFMachPort?
    private var eventTapSource: CFRunLoopSource?
    private var retryTimer: Timer?
    private let onDoubleTap: () -> Void

    init(onDoubleTap: @escaping () -> Void) {
        self.onDoubleTap = onDoubleTap
    }

    func start() {
        guard !started else { return }
        started = true
        let mask: NSEvent.EventTypeMask = .flagsChanged
        globalMonitor = NSEvent.addGlobalMonitorForEvents(matching: mask) { [weak self] event in
            self?.handle(event)
        }
        localMonitor = NSEvent.addLocalMonitorForEvents(matching: mask) { [weak self] event in
            self?.handle(event)
            return event
        }
        installEventTap()
        retryTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.installEventTap()
        }
    }

    private func installEventTap() {
        guard eventTap == nil else { return }
        let eventMask = CGEventMask(1 << CGEventType.flagsChanged.rawValue)
        let refcon = UnsafeMutableRawPointer(Unmanaged.passUnretained(self).toOpaque())
        eventTap = CGEvent.tapCreate(
            tap: .cgSessionEventTap,
            place: .headInsertEventTap,
            options: .listenOnly,
            eventsOfInterest: eventMask,
            callback: { _, _, event, refcon in
                guard let refcon else { return Unmanaged.passUnretained(event) }
                let monitor = Unmanaged<CommandDoubleTapMonitor>
                    .fromOpaque(refcon)
                    .takeUnretainedValue()
                let keyCode = event.getIntegerValueField(.keyboardEventKeycode)
                monitor.handle(keyCode: keyCode, flags: event.flags)
                return Unmanaged.passUnretained(event)
            },
            userInfo: refcon
        )
        if let eventTap {
            eventTapSource = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, eventTap, 0)
            if let eventTapSource {
                CFRunLoopAddSource(CFRunLoopGetMain(), eventTapSource, .commonModes)
                CGEvent.tapEnable(tap: eventTap, enable: true)
            }
        }
    }

    private func handle(_ event: NSEvent) {
        handle(
            keyCode: Int64(event.keyCode),
            flags: CGEventFlags(rawValue: UInt64(event.modifierFlags.rawValue))
        )
    }

    private func handle(keyCode: Int64, flags: CGEventFlags) {
        guard (keyCode == 54 || keyCode == 55), flags.contains(.maskCommand) else {
            return
        }
        let now = ProcessInfo.processInfo.systemUptime
        // 同一次按键会被 global/local/eventTap 三个通道重复上报，
        // 间隔小于 50ms 视为同一次按下，只计一次，避免单击被误判为双击。
        if now - lastHandledAt < 0.05 {
            return
        }
        lastHandledAt = now
        if now - lastCommandDown <= 0.42 {
            lastCommandDown = 0
            DispatchQueue.main.async { [onDoubleTap] in onDoubleTap() }
        } else {
            lastCommandDown = now
        }
    }

    deinit {
        if let globalMonitor { NSEvent.removeMonitor(globalMonitor) }
        if let localMonitor { NSEvent.removeMonitor(localMonitor) }
        retryTimer?.invalidate()
        if let eventTap, let eventTapSource {
            CFRunLoopRemoveSource(CFRunLoopGetMain(), eventTapSource, .commonModes)
            CFMachPortInvalidate(eventTap)
        }
    }
}

final class QuickLauncherController: NSWindowController, NSWindowDelegate {
    private let content = QuickLauncherView()

    init() {
        let window = LauncherPanel(
            contentRect: NSRect(x: 0, y: 0, width: 680, height: 58),
            styleMask: [.borderless, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        window.level = .floating
        window.isOpaque = false
        window.backgroundColor = .clear
        window.alphaValue = 1
        window.hasShadow = false
        window.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        window.isMovableByWindowBackground = true
        window.appearance = NSAppearance(named: .darkAqua)
        window.contentView = content
        super.init(window: window)
        window.delegate = self
        content.onDismiss = { [weak self] in self?.close() }
        content.onHeightChange = { [weak self] height in self?.resize(to: height) }
    }

    required init?(coder: NSCoder) { nil }

    func toggle() {
        if window?.isVisible == true {
            close()
        } else {
            show()
        }
    }

    func show() {
        guard let window, let screen = NSScreen.main else { return }
        content.reset()
        let x = screen.visibleFrame.midX - window.frame.width / 2
        let y = screen.visibleFrame.maxY - 170
        window.setFrameOrigin(NSPoint(x: x, y: y))
        NSApplication.shared.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        window.makeFirstResponder(content.searchField)
    }

    private func resize(to height: CGFloat) {
        guard let window else { return }
        var frame = window.frame
        let top = frame.maxY
        frame.size.height = height
        frame.origin.y = top - height
        // 顶部位置不变，避免结果数量变化时系统动画在上下方向间跳变。
        window.setFrame(frame, display: true, animate: false)
    }

    func windowDidResignKey(_ notification: Notification) {
        close()
    }
}

private final class LauncherPanel: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { true }
}

private final class DarkSelectedRowView: NSTableRowView {
    override func drawSelection(in dirtyRect: NSRect) {
        guard isSelected else { return }
        // 候选项选中背景色：RGB(54, 129, 136)，最后一个参数是透明度。
        NSColor(srgbRed: 54 / 255, green: 129 / 255, blue: 136 / 255, alpha: 1).setFill()
        NSBezierPath(roundedRect: bounds, xRadius: 8, yRadius: 8).fill()
    }
}

private final class LauncherResultCellView: NSTableCellView {
    init(result: LauncherResult) {
        super.init(frame: .zero)
        translatesAutoresizingMaskIntoConstraints = false

        let icon = NSImageView()
        icon.translatesAutoresizingMaskIntoConstraints = false
        icon.image = result.icon
        icon.imageScaling = .scaleProportionallyUpOrDown

        let title = NSTextField(labelWithString: result.title)
        title.font = .systemFont(ofSize: 18, weight: .semibold)
        title.lineBreakMode = .byTruncatingTail
        title.maximumNumberOfLines = 1

        let textStack = NSStackView()
        textStack.translatesAutoresizingMaskIntoConstraints = false
        textStack.orientation = .vertical
        textStack.alignment = .leading
        textStack.spacing = 0
        textStack.setContentHuggingPriority(.defaultLow, for: .horizontal)
        textStack.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        textStack.addArrangedSubview(title)
        if !result.subtitle.isEmpty {
            let subtitle = NSTextField(labelWithString: result.subtitle)
            subtitle.font = .systemFont(ofSize: 12)
            subtitle.textColor = .secondaryLabelColor
            subtitle.lineBreakMode = .byTruncatingTail
            subtitle.maximumNumberOfLines = 1
            textStack.addArrangedSubview(subtitle)
        }

        let rowStack = NSStackView(views: [icon, textStack])
        rowStack.translatesAutoresizingMaskIntoConstraints = false
        rowStack.orientation = .horizontal
        rowStack.alignment = .centerY
        rowStack.spacing = 12
        rowStack.distribution = .fill
        addSubview(rowStack)

        NSLayoutConstraint.activate([
            rowStack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 12),
            rowStack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -12),
            rowStack.topAnchor.constraint(greaterThanOrEqualTo: topAnchor, constant: 8),
            rowStack.bottomAnchor.constraint(lessThanOrEqualTo: bottomAnchor, constant: -8),
            rowStack.centerYAnchor.constraint(equalTo: centerYAnchor),
            icon.widthAnchor.constraint(equalToConstant: 36),
            icon.heightAnchor.constraint(equalToConstant: 36)
        ])
    }

    required init?(coder: NSCoder) { nil }
}

private final class QuickLauncherView: NSView, NSTextFieldDelegate, NSTableViewDataSource, NSTableViewDelegate {
    /// 快捷窗口的布局参数。调整尺寸时优先修改这里，避免在各个 frame 中重复写死数值。
    private enum Layout {
        /// 输入框和结果列表距离窗口左右边缘的距离。
        static let horizontalInset: CGFloat = 10
        /// 输入框顶部、结果列表底部距离窗口边缘的距离。
        static let verticalInset: CGFloat = 16
        /// 输入框背景高度。
        static let inputHeight: CGFloat = 56
        /// 输入文字距离输入框左右边缘的距离。
        static let inputHorizontalPadding: CGFloat = 8
        /// 输入文字字号；调整输入框高度后可同步调整此值保持比例。
        static let inputFontSize: CGFloat = 32
        /// 输入框和结果列表之间的垂直间距。
        static let resultGap: CGFloat = 10
        /// 每条候选结果的高度，同时影响结果图标和两行文字的垂直位置。
        /// 结果包含标题和副标题，建议不要设置得小于 52，以免文字基线被裁切。
        static let rowHeight: CGFloat = 56
        /// 无结果时的窗口高度：顶部边距 + 输入框高度 + 底部边距。
        static let collapsedHeight: CGFloat = verticalInset * 2 + inputHeight
    }

    let searchField = NSTextField()
    var onDismiss: (() -> Void)?
    var onHeightChange: ((CGFloat) -> Void)?

    private let tableView = NSTableView()
    private let scrollView = NSScrollView()
    private let inputBackground = NSView()
    private let contentStack = NSStackView()
    private var resultsHeightConstraint: NSLayoutConstraint!
    private var results: [LauncherResult] = []
    /// 当前直接展示的结果行数；窗口、滚动区域和表格都使用同一数值计算高度。
    private var visibleRowCount = 0
    private var applications: [InstalledApplication] = []
    private var translationTask: URLSessionDataTask?
    private var translationWork: DispatchWorkItem?
    private var queryVersion = UUID()

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        wantsLayer = true
        layer?.cornerRadius = 8
        layer?.cornerCurve = .continuous
        // 用系统毛玻璃替换原来的纯色背景，窗口后方内容以磨砂方式透出。
        layer?.masksToBounds = true
        layer?.borderWidth = 0

        let effectView = NSVisualEffectView()
        effectView.translatesAutoresizingMaskIntoConstraints = false
        // menu 材质比 hudWindow/underWindowBackground 更深更沉稳，仍保持磨砂质感。
        effectView.material = .menu
        effectView.blendingMode = .behindWindow
        effectView.state = .active
        addSubview(effectView, positioned: .below, relativeTo: nil)
        NSLayoutConstraint.activate([
            effectView.leadingAnchor.constraint(equalTo: leadingAnchor),
            effectView.trailingAnchor.constraint(equalTo: trailingAnchor),
            effectView.topAnchor.constraint(equalTo: topAnchor),
            effectView.bottomAnchor.constraint(equalTo: bottomAnchor)
        ])

        inputBackground.wantsLayer = true
        // 输入框深色内嵌，与较亮的磨砂背景拉开层次，输入框清晰可见。
        inputBackground.layer?.backgroundColor = NSColor(
            srgbRed: 48 / 255,
            green: 48 / 255,
            blue: 48 / 255,
            alpha: 0.9
        ).cgColor
        inputBackground.layer?.cornerRadius = 8
        inputBackground.layer?.cornerCurve = .continuous
        inputBackground.translatesAutoresizingMaskIntoConstraints = false

        searchField.placeholderString = nil
        searchField.font = .systemFont(ofSize: Layout.inputFontSize, weight: .medium)
        searchField.focusRingType = .none
        searchField.isBezeled = false
        searchField.drawsBackground = false
        searchField.textColor = .white
        searchField.delegate = self
        searchField.translatesAutoresizingMaskIntoConstraints = false
        inputBackground.addSubview(searchField)
        NSLayoutConstraint.activate([
            searchField.leadingAnchor.constraint(
                equalTo: inputBackground.leadingAnchor,
                constant: Layout.inputHorizontalPadding
            ),
            searchField.trailingAnchor.constraint(
                equalTo: inputBackground.trailingAnchor,
                constant: -Layout.inputHorizontalPadding
            ),
            searchField.centerYAnchor.constraint(equalTo: inputBackground.centerYAnchor)
        ])

        let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("result"))
        column.resizingMask = .autoresizingMask
        tableView.addTableColumn(column)
        tableView.headerView = nil
        tableView.backgroundColor = .clear
        tableView.rowHeight = Layout.rowHeight
        tableView.intercellSpacing = .zero
        tableView.selectionHighlightStyle = .regular
        // 默认的 automatic style 会给行加上 10px 顶部内边距，导致行底边超出
        // 滚动区域而被裁掉，表现为候选结果边界显示不全；且 fullWidth 会让表格
        // 宽度超出滚动区，出现左右滚动。plain 无内边距且宽度与列一致。
        tableView.style = .plain
        tableView.dataSource = self
        tableView.delegate = self
        tableView.autoresizingMask = [.width]
        scrollView.documentView = tableView
        scrollView.borderType = .noBorder
        scrollView.contentInsets = NSEdgeInsets(top: 0, left: 0, bottom: 0, right: 0)
        scrollView.drawsBackground = false
        scrollView.hasVerticalScroller = false
        scrollView.translatesAutoresizingMaskIntoConstraints = false

        contentStack.translatesAutoresizingMaskIntoConstraints = false
        contentStack.orientation = .vertical
        contentStack.alignment = .width
        contentStack.spacing = Layout.resultGap
        contentStack.addArrangedSubview(inputBackground)
        contentStack.addArrangedSubview(scrollView)
        addSubview(contentStack)

        resultsHeightConstraint = scrollView.heightAnchor.constraint(equalToConstant: 0)
        resultsHeightConstraint.isActive = true
        scrollView.isHidden = true

        NSLayoutConstraint.activate([
            contentStack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: Layout.horizontalInset),
            contentStack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -Layout.horizontalInset),
            contentStack.topAnchor.constraint(equalTo: topAnchor, constant: Layout.verticalInset),
            contentStack.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -Layout.verticalInset),
            inputBackground.heightAnchor.constraint(equalToConstant: Layout.inputHeight)
        ])

        loadApplications()
    }

    required init?(coder: NSCoder) { nil }

    func reset() {
        translationTask?.cancel()
        translationWork?.cancel()
        searchField.stringValue = ""
        results = []
        visibleRowCount = 0
        resultsHeightConstraint.constant = 0
        scrollView.isHidden = true
        tableView.reloadData()
        onHeightChange?(Layout.collapsedHeight)
    }

    func controlTextDidChange(_ obj: Notification) {
        updateResults(for: searchField.stringValue)
    }

    func control(_ control: NSControl, textView: NSTextView, doCommandBy commandSelector: Selector) -> Bool {
        switch commandSelector {
        case #selector(NSResponder.moveUp(_:)):
            select(offset: -1)
        case #selector(NSResponder.moveDown(_:)):
            select(offset: 1)
        case #selector(NSResponder.insertNewline(_:)):
            activateSelection()
        case #selector(NSResponder.cancelOperation(_:)):
            onDismiss?()
        default:
            return false
        }
        return true
    }

    func numberOfRows(in tableView: NSTableView) -> Int { results.count }

    func tableView(_ tableView: NSTableView, rowViewForRow row: Int) -> NSTableRowView? {
        DarkSelectedRowView()
    }

    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
        // 表格在 reloadData 的内部重排期间可能短暂请求旧行号，不能直接用它访问最新结果数组。
        guard results.indices.contains(row) else { return nil }
        return LauncherResultCellView(result: results[row])
    }

    private func updateResults(for rawQuery: String) {
        translationTask?.cancel()
        translationWork?.cancel()
        let query = rawQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        queryVersion = UUID()
        guard !query.isEmpty else {
            setResults([])
            return
        }
        let lowerQuery = query.lowercased()
        // 翻译前缀：fy（双字符）或 t（单字符），两者等价。
        let usesFy = lowerQuery == "fy" || lowerQuery.hasPrefix("fy ") || lowerQuery.hasPrefix("fy\t")
        let usesT = lowerQuery == "t" || lowerQuery.hasPrefix("t ") || lowerQuery.hasPrefix("t\t")
        if usesFy || usesT {
            let dropCount = usesFy ? 2 : 1
            let prefixName = usesFy ? "fy" : "t"
            let text = String(query.dropFirst(dropCount)).trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else {
                setResults([.message("在 \(prefixName) 后输入要翻译的内容")])
                return
            }
            setResults([.message("正在翻译…")])
            scheduleTranslation(text, version: queryVersion)
            return
        }
        var parser = MathParser(query)
        if let value = try? parser.parse() {
            setResults([.calculation(Self.format(value))])
            return
        }
        if query.range(of: #"^[0-9\s+\-*/^().]+$"#, options: .regularExpression) != nil {
            setResults([])
            return
        }
        let normalized = query.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
        let matches = applications
            .filter { $0.searchText.contains(normalized) }
            .sorted {
                let leftPrefix = $0.searchText.hasPrefix(normalized)
                let rightPrefix = $1.searchText.hasPrefix(normalized)
                return leftPrefix == rightPrefix ? $0.name.count < $1.name.count : leftPrefix
            }
            .prefix(8)
            .map { LauncherResult.application(name: $0.name, url: $0.url, icon: $0.icon) }
        setResults(matches.isEmpty ? [.message("没有找到匹配的应用")]: Array(matches))
    }

    private func setResults(_ value: [LauncherResult]) {
        results = value
        // 窗口最多直接展示 6 条结果，更多结果在列表内部滚动查看。
        visibleRowCount = min(value.count, 6)
        resultsHeightConstraint.constant = CGFloat(visibleRowCount) * Layout.rowHeight
        scrollView.isHidden = value.isEmpty
        tableView.reloadData()
        if !value.isEmpty { tableView.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false) }
        // 展开高度 = 收起高度 + 输入框/列表间距 + 可见行数 × 单行高度。
        let expandedHeight = Layout.collapsedHeight
            + Layout.resultGap
            + CGFloat(visibleRowCount) * Layout.rowHeight
        onHeightChange?(value.isEmpty ? Layout.collapsedHeight : expandedHeight)
        // reloadData 完成并让 AppKit 更新行视图后再回到首行；提前滚动会让旧行索引访问新数组。
        DispatchQueue.main.async { [weak self] in
            guard let self, !self.results.isEmpty else { return }
            self.layoutSubtreeIfNeeded()
            self.updateTableDocumentSize()
            self.tableView.scrollRowToVisible(0)
        }
    }

    /// NSTableView 是滚动视图的 documentView，只在这里同步可滚动内容尺寸，不参与界面位置布局。
    private func updateTableDocumentSize() {
        let width = scrollView.contentSize.width
        tableView.frame = NSRect(
            x: 0,
            y: 0,
            width: width,
            height: CGFloat(results.count) * Layout.rowHeight
        )
        tableView.tableColumns.first?.width = width
    }

    private func select(offset: Int) {
        guard !results.isEmpty else { return }
        let current = max(0, tableView.selectedRow)
        let next = min(results.count - 1, max(0, current + offset))
        tableView.selectRowIndexes(IndexSet(integer: next), byExtendingSelection: false)
        tableView.scrollRowToVisible(next)
    }

    private func activateSelection() {
        guard results.indices.contains(tableView.selectedRow) else { return }
        switch results[tableView.selectedRow] {
        case .application(_, let url, _):
            NSWorkspace.shared.openApplication(at: url, configuration: .init()) { _, _ in }
            onDismiss?()
        case .calculation(let value), .translation(let value):
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(value, forType: .string)
            onDismiss?()
        case .message:
            NSSound.beep()
        }
    }

    private func loadApplications() {
        DispatchQueue.global(qos: .utility).async { [weak self] in
            let roots = [
                URL(fileURLWithPath: "/Applications"),
                URL(fileURLWithPath: "/System/Applications"),
                FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Applications")
            ]
            var found: [String: InstalledApplication] = [:]
            for root in roots {
                guard let enumerator = FileManager.default.enumerator(
                    at: root,
                    includingPropertiesForKeys: [.isDirectoryKey],
                    options: [.skipsHiddenFiles, .skipsPackageDescendants]
                ) else { continue }
                for case let url as URL in enumerator where url.pathExtension.lowercased() == "app" {
                    let bundle = Bundle(url: url)
                    let name = (bundle?.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String)
                        ?? (bundle?.object(forInfoDictionaryKey: "CFBundleName") as? String)
                        ?? url.deletingPathExtension().lastPathComponent
                    let bundleID = bundle?.bundleIdentifier ?? url.path
                    let search = "\(name) \(url.deletingPathExtension().lastPathComponent) \(bundleID)"
                        .folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
                    found[bundleID] = InstalledApplication(
                        name: name,
                        searchText: search,
                        url: url,
                        icon: NSWorkspace.shared.icon(forFile: url.path)
                    )
                }
            }
            DispatchQueue.main.async { self?.applications = Array(found.values) }
        }
    }

    private func scheduleTranslation(_ text: String, version: UUID) {
        let work = DispatchWorkItem { [weak self] in self?.translate(text, version: version) }
        translationWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35, execute: work)
    }

    private func translate(_ text: String, version: UUID) {
        let languagePair: String
        if Self.isEnglish(text) {
            languagePair = "en-US|zh-CN"
        } else if Self.containsChinese(text) {
            languagePair = "zh-CN|en-US"
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.setResults([.message("仅支持中英文互译")])
            }
            return
        }
        var components = URLComponents(string: "https://api.mymemory.translated.net/get")!
        components.queryItems = [
            .init(name: "q", value: text),
            .init(name: "langpair", value: languagePair)
        ]
        var request = URLRequest(url: components.url!)
        request.timeoutInterval = 12
        translationTask = URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            let translated: String? = data.flatMap { payload in
                guard
                    let root = try? JSONSerialization.jsonObject(with: payload) as? [String: Any],
                    root["responseStatus"] as? Int == 200,
                    let responseData = root["responseData"] as? [String: Any],
                    let translated = responseData["translatedText"] as? String,
                    !translated.isEmpty
                else { return nil }
                return translated
            }
            DispatchQueue.main.async {
                guard let self, self.queryVersion == version else { return }
                let statusCode = (response as? HTTPURLResponse)?.statusCode ?? 0
                if let translated {
                    self.setResults([.translation(translated)])
                } else {
                    let message = error == nil && (statusCode == 0 || (200..<300).contains(statusCode))
                        ? "翻译结果解析失败"
                        : "网络连接失败（\(statusCode)）"
                    self.setResults([.message(message)])
                }
            }
        }
        translationTask?.resume()
    }

    private static func format(_ value: Double) -> String {
        guard value.isFinite else { return "无法计算" }
        if value.rounded() == value, abs(value) < Double(Int64.max) { return String(Int64(value)) }
        return String(format: "%.10g", value)
    }

    private static func isEnglish(_ text: String) -> Bool {
        text.range(
            of: #"^[A-Za-z0-9\s.,!?;'\"()\[\]{}:—_+\-]+$"#,
            options: .regularExpression
        ) != nil
    }

    private static func containsChinese(_ text: String) -> Bool {
        text.range(of: #"[\u{4E00}-\u{9FFF}]"#, options: .regularExpression) != nil
    }

}

struct MathParser {
    private let characters: [Character]
    private var index = 0

    init(_ input: String) {
        characters = Array(input.replacingOccurrences(of: " ", with: ""))
    }

    mutating func parse() throws -> Double {
        let result = try expression()
        guard index == characters.count else { throw ParseError.invalid }
        return result
    }

    private mutating func expression() throws -> Double {
        var value = try term()
        while let op = peek(), op == "+" || op == "-" {
            index += 1
            let right = try term()
            value = op == "+" ? value + right : value - right
        }
        return value
    }

    private mutating func term() throws -> Double {
        var value = try power()
        while let op = peek(), op == "*" || op == "/" {
            index += 1
            let right = try power()
            guard op != "/" || right != 0 else { throw ParseError.invalid }
            value = op == "*" ? value * right : value / right
        }
        return value
    }

    private mutating func power() throws -> Double {
        var value = try unary()
        if peek() == "^" {
            index += 1
            value = pow(value, try power())
        }
        return value
    }

    private mutating func unary() throws -> Double {
        if peek() == "+" { index += 1; return try unary() }
        if peek() == "-" { index += 1; return -(try unary()) }
        return try primary()
    }

    private mutating func primary() throws -> Double {
        if peek() == "(" {
            index += 1
            let value = try expression()
            guard peek() == ")" else { throw ParseError.invalid }
            index += 1
            return value
        }
        let start = index
        while let char = peek(), char.isNumber || char == "." { index += 1 }
        guard start != index, let value = Double(String(characters[start..<index])) else {
            throw ParseError.invalid
        }
        return value
    }

    private func peek() -> Character? { index < characters.count ? characters[index] : nil }
    private enum ParseError: Error { case invalid }
}

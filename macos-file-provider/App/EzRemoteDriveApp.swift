import SwiftUI

@main
struct EzRemoteDriveApp: App {
    @StateObject private var model = AppModel()
    var body: some Scene {
        WindowGroup {
            ContentView().environmentObject(model)
        }
        .commands {
            CommandGroup(replacing: .newItem) {}
        }
    }
}


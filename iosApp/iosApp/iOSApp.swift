import SwiftUI
import UIKit
import Shared

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self)
    var appDelegate: AppDelegate

    var body: some Scene {
        WindowGroup {
            ComposeView(
                root: appDelegate.root,
                backDispatcher: appDelegate.backDispatcher
            )
            .ignoresSafeArea(.all)
        }
    }
}

class AppDelegate: NSObject, UIApplicationDelegate {
    private var stateKeeper = StateKeeperDispatcherKt.StateKeeperDispatcher(savedState: nil)
    var backDispatcher: BackDispatcher = BackDispatcherKt.BackDispatcher()

    private let appGraph = IosAppGraphKt.getIosAppGraph()

    lazy var root: RootComponent = {
        let context = DefaultComponentContext(
            lifecycle: ApplicationLifecycle(),
            stateKeeper: stateKeeper,
            instanceKeeper: nil,
            backHandler: backDispatcher
        )

        return appGraph.rootFactory.create(componentContext: context)
    }()

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // The AppDelegate is the mesh lifecycle owner on iOS (no foreground-service concept):
        // the BLE mesh runs while the process lives, kept alive in background by the
        // bluetooth-central/peripheral background modes.
        appGraph.meshLifecycleController.start()

        // Same launch-time Nostr/Tor bootstrap the Android Application runs (Tor, relay directory,
        // location notes, identity warm-up, DM ingest). Without it geohash channels have no relays.
        // Non-blocking: each step dispatches its own work.
        appGraph.appNetworkBootstrapper.start()
        return true
    }

    func applicationWillTerminate(_ application: UIApplication) {
        appGraph.meshLifecycleController.stop_()
    }

    func applicationWillEnterForeground(_ application: UIApplication) {
        appGraph.meshLifecycleController.setAppIsActive(active: true)
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        appGraph.meshLifecycleController.setAppIsActive(active: false)
    }

    func application(_ application: UIApplication, shouldSaveSecureApplicationState coder: NSCoder) -> Bool {
        StateKeeperUtilsKt.save(coder: coder, state: stateKeeper.save())
        return true
    }

    func application(_ application: UIApplication, shouldRestoreSecureApplicationState coder: NSCoder) -> Bool {
        // Restoration is armed but disabled for now, mirroring the Android side where the root is
        // always rebuilt fresh; flip on once per-screen state is worth preserving across relaunch.
        // stateKeeper = StateKeeperDispatcherKt.StateKeeperDispatcher(savedState: StateKeeperUtilsKt.restore(coder: coder))
        return true
    }
}

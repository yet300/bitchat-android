import UIKit
import SwiftUI
import Shared

struct ComposeView: UIViewControllerRepresentable {
    let root: RootComponent
    let backDispatcher: BackDispatcher

    func makeUIViewController(context: Self.Context) -> UIViewController {
        MainViewControllerKt.MainViewController(
            root: root,
            backDispatcher: backDispatcher
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

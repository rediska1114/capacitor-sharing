import Capacitor
import Foundation

/// Please read the Capacitor iOS Plugin Development Guide
/// here: https://capacitorjs.com/docs/plugins/ios
@objc(SharingPlugin)
public class SharingPlugin: CAPPlugin {

  @objc func share(_ call: CAPPluginCall) {
    var image: UIImage? = nil

    if let imageBase64 = call.getString("imageBase64") {
      image = UIImage(data: base64StringToData(imageBase64)!)
    }

    var url: URL? = nil
    if let urlStr = call.getString("url") {
      url = URL(string: urlStr)
    }

    let text = call.getString("text")

    DispatchQueue.main.async {
      let activityItems = [text as Any, image as Any, url as Any].compactMap({
        $0
      })

      let actionController = UIActivityViewController(
        activityItems: activityItems, applicationActivities: nil)
      if let sender = self.bridge?.viewController?.view {
        actionController.popoverPresentationController?.sourceView = sender
      }

      actionController.completionWithItemsHandler = {
        (activityType, completed, _ returnedItems, activityError) in
        if activityError != nil {
          call.reject("Error sharing item", nil, activityError)
          return
        }

        call.resolve([
          "status": completed ? "success" : "cancelled",
          "target": activityType?.rawValue ?? "",
        ])

      }
      if self.bridge?.viewController?.presentedViewController != nil {
        call.reject("Can't share while sharing is in progress")
        return
      }
      self.setCenteredPopover(actionController)
      self.bridge?.viewController?.present(actionController, animated: true, completion: nil)

    }
    call.resolve()
  }

  @objc func canShareTo(_ call: CAPPluginCall) {
    guard let shareTo = call.getString("shareTo") else {
      call.reject("Must provide a shareTo")
      return
    }

    if let handler = createHandler(for: shareTo) {
      handler.call = call
      handler.checkAvailability { isAvailable, error in
        if let error = error {
          call.reject(error)
        } else {
          call.resolve(["value": isAvailable])
        }
      }
    } else {
      call.reject("Unsupported target")
    }
  }

  @objc func shareTo(_ call: CAPPluginCall) {
    guard let shareTo = call.getString("shareTo") else {
      call.reject("Must provide a shareTo")
      return
    }

    if let handler = createHandler(for: shareTo) {
      handler.call = call
      handler.share { success, error in
        if let error = error {
          call.reject(error)
        } else {
          call.resolve(["value": success])
        }
      }
    } else {
      call.reject("Unsupported target")
    }
  }

  private func createHandler(for target: String) -> ShareTargetHandler? {
    switch target {
    case "instagramStories":
      return MetaHandler(platform: "instagram", placement: "stories")
    case "facebookStories":
      return MetaHandler(platform: "facebook", placement: "stories")
    case "native":
      return NativeHandler()
    default:
      return nil
    }
  }
}

func base64StringToData(_ str: String) -> Data? {
  if str.contains("data:image") {
    guard let url = URL(string: str) else { return nil }
    return try? Data(contentsOf: url)
  } else {
    return Data(base64Encoded: str, options: .ignoreUnknownCharacters)
  }
}

class ShareTargetHandler {
  public var call: CAPPluginCall?

  init() {}

  func checkAvailability(completion: @escaping (Bool, String?) -> Void) {}
  func share(completion: @escaping (Bool, String?) -> Void) {}
}

class MetaHandler: ShareTargetHandler {
  private let platform: String  // facebook or instagram
  private let placement: String  // stories or feed

  init(platform: String, placement: String) {
    self.platform = platform
    self.placement = placement
  }

  override func checkAvailability(completion: @escaping (Bool, String?) -> Void) {
    DispatchQueue.main.async {
      guard let facebookAppId = self.call?.getString("facebookAppId") else {
        completion(false, "Must provide a facebookAppId")
        return
      }

      let value = UIApplication.shared.canOpenURL(self.getShareUrl(facebookAppId))

      completion(value, nil)
    }
  }

  override func share(completion: @escaping (Bool, String?) -> Void) {
    DispatchQueue.main.async {
        guard let facebookAppId = self.call?.getString("facebookAppId") else {
        completion(false, "Must provide a facebookAppId")
        return
      }
        
        guard let call = self.call else {
            completion(false, "Must provide a call")
            return
        }

        let shareUrl = self.getShareUrl(facebookAppId)

      var backgroundImage: UIImage? = nil
        if let backgroundImageBase64 = call.getString("backgroundImageBase64") {
        backgroundImage = UIImage(data: base64StringToData(backgroundImageBase64)!)
      }
        let backgroundTopColor = call.getString("backgroundTopColor")
        let backgroundBottomColor = call.getString("backgroundBottomColor")

      var stickerImage: UIImage? = nil
        if let stickerImageBase64 = call.getString("stickerImageBase64") {
        stickerImage = UIImage(data: base64StringToData(stickerImageBase64)!)
      }

      var pasteboardItems: [String: Any] = [:]

      if let backgroundImageData = backgroundImage?.pngData() {
        pasteboardItems["com.\(self.platform).sharedSticker.backgroundImage"] = backgroundImageData
      }

      if let stickerImageData = stickerImage?.pngData() {
        pasteboardItems["com.\(self.platform).sharedSticker.stickerImage"] = stickerImageData
      }

      if let backgroundTopColor = backgroundTopColor {
        pasteboardItems["com.\(self.platform).sharedSticker.backgroundTopColor"] = backgroundTopColor
      }

      if let backgroundBottomColor = backgroundBottomColor {
        pasteboardItems["com.\(self.platform).sharedSticker.backgroundBottomColor"] = backgroundBottomColor
      }

      if self.platform == "facebook" {
        pasteboardItems["com.facebook.sharedSticker.appID"] = facebookAppId
      }

      let pasteboardOptions: [UIPasteboard.OptionsKey: Any] = [
        .expirationDate: Date().addingTimeInterval(60 * 5)
      ]
      UIPasteboard.general.setItems([pasteboardItems], options: pasteboardOptions)
      UIApplication.shared.open(
        shareUrl, options: [:], completionHandler: nil)

      completion(true, nil)
    }

  }

  @objc private func getShareUrl(_ facebookAppId: String) -> URL {
    return URL(string: "\(platform)-\(placement)://share?source_application=\(facebookAppId)")!
  }
}

class NativeHandler: ShareTargetHandler {
  override func checkAvailability(completion: @escaping (Bool, String?) -> Void) {
    completion(true, nil)  // Always available
  }
  override func share(completion: @escaping (Bool, String?) -> Void) {
    completion(true, nil)
  }
}

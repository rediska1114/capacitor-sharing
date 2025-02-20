package com.rediska1114.plugins.sharing

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.activity.result.ActivityResult
import androidx.core.content.FileProvider
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.File
import java.io.FileOutputStream

@CapacitorPlugin(name = "Sharing")
class SharingPlugin : Plugin() {
  private var stopped = false
  private var isPresenting = false
  private var chosenComponent: ComponentName? = null

  private lateinit var shareReceiver: BroadcastReceiver

  override fun load() {
    super.load()

    shareReceiver =
        object : BroadcastReceiver() {
          override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) {
              return
            }
            chosenComponent = getChosenComponent(intent)
          }
        }

    val shareResultAction = getShareResultAction(context)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.registerReceiver(
          shareReceiver, IntentFilter(shareResultAction), Context.RECEIVER_NOT_EXPORTED)
    } else {
      context.registerReceiver(shareReceiver, IntentFilter(shareResultAction))
    }
  }

  private fun getChosenComponent(intent: Intent): ComponentName? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
    } else {
      intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
    }
  }

  @ActivityCallback
  fun sharingResult(call: PluginCall, result: ActivityResult) {
    val cancelled = result.resultCode == Activity.RESULT_CANCELED && !stopped
    isPresenting = false

    call.resolve(getResultData(status = if (cancelled) "cancelled" else "success"))
  }

  @PluginMethod
  fun share(call: PluginCall) {
    if (!isPresenting) {
      val imageHelper = ImageHelper(context)

      val imageBase64 = call.getString("imageBase64")
      val text = call.getString("text")
      val title = call.getString("title")
      val dialogTitle = call.getString("dialogTitle", "Share via")
      val url = call.getString("url")

      // clear original imageBase64 to avoid content too large error
      // because if no, this bug occurs https://github.com/ionic-team/capacitor/issues/6211
      call.data.put("imageBase64", "")

      val shareIntent = Intent(Intent.ACTION_SEND)

      if (!title.isNullOrEmpty()) {
        shareIntent.putExtra(Intent.EXTRA_TITLE, title)
      }

      var textContent = ""
      shareIntent.setTypeAndNormalize("text/plain")

      if (!imageBase64.isNullOrEmpty()) {
        val bitmap =
            imageHelper.decodeBase64ToBitmap(imageBase64) ?: return call.reject("Invalid image")
        val contentUri = imageHelper.bitmapToUri(bitmap)
        shareIntent.setDataAndType(contentUri, imageHelper.getMediaType(contentUri))
        shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri)
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }

      textContent = listOfNotNull(text, url).joinToString("\n")

      if (textContent.isNotEmpty()) {
        shareIntent.putExtra(Intent.EXTRA_TEXT, textContent)
      }

      var flags = PendingIntent.FLAG_UPDATE_CURRENT
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        flags = flags or PendingIntent.FLAG_MUTABLE
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        flags = flags or PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
      }

      val resultIntent = Intent(getShareResultAction(context))
      val pendingIntent = PendingIntent.getBroadcast(context, 0, resultIntent, flags)

      val chooser = Intent.createChooser(shareIntent, dialogTitle, pendingIntent.intentSender)
      chooser.addCategory(Intent.CATEGORY_DEFAULT)
      chosenComponent = null
      stopped = false
      isPresenting = true
      startActivityForResult(call, chooser, "sharingResult")
    } else {
      call.reject("Can't share while sharing is in progress")
    }
  }

  @PluginMethod
  fun canShareTo(call: PluginCall) {
    val shareTo = call.getString("shareTo") ?: return call.reject("Must provide a shareTo")

    val handler = createHandler(shareTo) ?: return call.reject("Unsupported target")

    handler.call = call
    handler.context = context
    handler.activity = activity
    handler.checkAvailability { isAvailable, error ->
      if (error != null) {
        call.reject(error)
      } else {
        val data = JSObject()
        data.put("value", isAvailable)
        call.resolve(data)
      }
    }
  }

  @PluginMethod
  fun shareTo(call: PluginCall) {
    val shareTo = call.getString("shareTo") ?: return call.reject("Must provide a shareTo")

    val handler = createHandler(shareTo) ?: return call.reject("Unsupported target")

    handler.call = call
    handler.context = context
    handler.activity = activity
    handler.share { success, error ->
      if (error != null) {
        call.reject(error)
      } else {
        val data = JSObject()
        data.put("value", success)
        call.resolve(data)
      }
    }
  }

  override fun handleOnDestroy() {
    context.unregisterReceiver(shareReceiver)
    super.handleOnDestroy()
  }

  override fun handleOnStop() {
    super.handleOnStop()
    stopped = true
  }

  private fun getShareResultAction(context: Context): String {
    val intentName = context.packageName + ".SHARE_RESULT"
    return intentName
  }

  private fun getResultData(status: String = "success"): JSObject {
    val data = JSObject()
    data.put("status", status)
    data.put("target", chosenComponent?.toShortString() ?: "")

    return data
  }

  private fun createHandler(target: String): ShareTargetHandler? {
    return when (target) {
      "instagramStories" -> MetaHandler(platform = "instagram", placement = "stories")
      "facebookStories" -> MetaHandler(platform = "facebook", placement = "stories")
      "native" -> NativeHandler()
      else -> null
    }
  }
}

class ImageHelper(private val context: Context) {
  fun bitmapToUri(bitmap: Bitmap): Uri {
    val file = File(context.cacheDir, "shared_image.png")
    val fileOutputStream = FileOutputStream(file)
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fileOutputStream)
    fileOutputStream.close()
    val contentUri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    return contentUri
  }

  fun decodeBase64ToBitmap(base64Str: String): Bitmap? {
    return try {
      val base64Image =
          if (base64Str.contains("data:image")) {
            base64Str.substringAfter(",") // Remove the data:image part
          } else {
            base64Str
          }

      val decodedBytes = Base64.decode(base64Image, Base64.DEFAULT)
      BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
    } catch (e: Exception) {
      null
    }
  }

  fun getMediaType(uri: Uri): String? {
    return context.contentResolver.getType(uri)
  }
}

interface ShareTargetHandler {
  var call: PluginCall?
  var context: Context?
  var activity: Activity?

  fun checkAvailability(completion: (Boolean, String?) -> Unit?): Unit?

  fun share(completion: (Boolean, String?) -> Unit?): Unit?
}

class MetaHandler(
    private val platform: String, // "facebook" or "instagram"
    private val placement: String, // "stories" or "feed"
) : ShareTargetHandler {

  override var call: PluginCall? = null
  override var context: Context? = null
  override var activity: Activity? = null

  override fun checkAvailability(completion: (Boolean, String?) -> Unit?): Unit? {
    //    val facebookAppId =
    //        call?.getString("facebookAppId") ?: return completion(false, "Must provide a
    // facebookAppId")
    val intent = Intent(getActionName()).apply { type = "image/*" }

    // This is your application's FB ID
    // intent.putExtra("source_application", facebookAppId)

    // Verify that the activity resolves the intent and start it
    val packageManager = activity!!.packageManager
    val canShare = intent.resolveActivity(packageManager) != null

    return completion(canShare, null)
  }

  override fun share(completion: (Boolean, String?) -> Unit?): Unit? {
    val imageHelper = ImageHelper(context!!)

    val facebookAppId =
        call?.getString("facebookAppId")
            ?: return completion(false, ("Must provide a facebookAppId"))

    val backgroundImageUri =
        call
            ?.getString("backgroundImageBase64")
            ?.let { imageHelper.decodeBase64ToBitmap(it) }
            ?.let { imageHelper.bitmapToUri(it) }

    val stickerImageUri =
        call
            ?.getString("stickerImageBase64")
            ?.let { imageHelper.decodeBase64ToBitmap(it) }
            ?.let { imageHelper.bitmapToUri(it) }
    val backgroundTopColor = call?.getString("backgroundTopColor")
    val backgroundBottomColor = call?.getString("backgroundBottomColor")

    // Instantiate an intent
    val intent = Intent(getActionName())

    // This is your application's FB ID
    if (platform == "facebook") {
      intent.putExtra("com.facebook.platform.extra.APPLICATION_ID", facebookAppId)
    } else {
      intent.putExtra("source_application", facebookAppId)
    }

    if (backgroundImageUri !== null) {
      // Attach your image to the intent from a URI
      intent.setDataAndType(backgroundImageUri, imageHelper.getMediaType(backgroundImageUri))
      activity!!.grantUriPermission(
          getPackageName(), backgroundImageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    if (stickerImageUri !== null) {
      intent.setType(imageHelper.getMediaType(stickerImageUri))
      intent.putExtra("interactive_asset_uri", stickerImageUri)
      activity!!.grantUriPermission(
          getPackageName(), stickerImageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    if (!backgroundTopColor.isNullOrEmpty()) {
      intent.putExtra("top_background_color", backgroundTopColor)
    }
    if (!backgroundBottomColor.isNullOrEmpty()) {
      intent.putExtra("bottom_background_color", backgroundBottomColor)
    }

    // Verify that the activity resolves the intent and start it
    if (activity!!.packageManager.resolveActivity(intent, 0) != null) {
      context!!.startActivity(intent)
      return completion(true, null)
    } else {
      return completion(false, "Instagram not installed")
    }
  }

  private fun getActionName(): String {
    val action = if (placement == "stories") "ADD_TO_STORY" else "ADD_TO_FEED"
    return if (platform == "facebook") {
        "com.facebook.stories.$action"
    } else {
        "com.instagram.share.$action"
    }
  }

  private fun getPackageName(): String {
    return if (platform == "facebook") {
      "com.facebook.katana"
    } else {
      "com.instagram.android"
    }
  }
}

class NativeHandler : ShareTargetHandler {
  override var call: PluginCall? = null
  override var context: Context? = null
  override var activity: Activity? = null

  override fun checkAvailability(completion: (Boolean, String?) -> Unit?): Unit? {
    return completion(true, null) // Always available
  }

  override fun share(completion: (Boolean, String?) -> Unit?): Unit? {
    return completion(true, null)
  }
}

package com.backgroundremover

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.math.pow
import android.webkit.MimeTypeMap
import kotlinx.coroutines.*

class BackgroundRemoverModule internal constructor(context: ReactApplicationContext) :
  BackgroundRemoverSpec(context) {
  private var segmenter: Segmenter? = null

  override fun getName(): String {
    return NAME
  }

  @ReactMethod
  override fun removeBackground(imageURI: String, promise: Promise) {
    val segmenter = this.segmenter ?: createSegmenter()
    try {
      val image = getImageBitmap(imageURI).copy(Bitmap.Config.ARGB_8888, true)
      val inputImage = InputImage.fromBitmap(image, 0)

      segmenter.process(inputImage).addOnFailureListener { e ->
        promise.reject(e)
      }.addOnSuccessListener { result ->
        val maskBuffer = result.buffer
        val mask = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)

        for (y in 0 until result.height) {
          for (x in 0 until result.width) {
            val alpha = maskBuffer.getFloat().pow(4)
            val color = if (alpha > 0.1) Color.argb((alpha * 255).toInt(), 0, 0, 0) else Color.TRANSPARENT
            mask.setPixel(x, y, color)
          }
        }

        val mutableImage = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(mutableImage)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.drawBitmap(image, 0f, 0f, null)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        canvas.drawBitmap(mask, 0f, 0f, paint)

        val fileName = URI(imageURI).path.split("/").last()
        val savedImageURI = saveImage(mutableImage, fileName)
        promise.resolve(savedImageURI)
      }
    } catch (e: Exception) {
      promise.reject(e)
    }
  }


  private fun createSegmenter(): Segmenter {
    val options =
      SelfieSegmenterOptions.Builder()
        .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
        .build()

    val segmenter = Segmentation.getClient(options)
    this.segmenter = segmenter

    return segmenter
  }

  private fun getImageBitmap(imageURI: String): Bitmap {
    val uri = Uri.parse(imageURI)

    return if (uri.scheme == "http" || uri.scheme == "https") {
      val localFile = downloadImage(uri.toString())
      decodeBitmapFromUri(Uri.fromFile(localFile))
    } else {
      decodeBitmapFromUri(uri)
    }
  }

  private fun decodeBitmapFromUri(uri: Uri): Bitmap {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      ImageDecoder.decodeBitmap(
        ImageDecoder.createSource(
          reactApplicationContext.contentResolver,
          uri
        )
      ).copy(Bitmap.Config.ARGB_8888, true)
    } else {
      MediaStore.Images.Media.getBitmap(reactApplicationContext.contentResolver, uri)
    }
  }

  private fun downloadImage(imageUrl: String): File {
    return runBlocking {
      withContext(Dispatchers.IO) {
        val url = URL(imageUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.connect()

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
          throw Exception("Failed to download image: ${connection.responseMessage}")
        }

        var extension = MimeTypeMap.getFileExtensionFromUrl(imageUrl)
        if (extension.isNullOrEmpty()) {
          val contentType = connection.contentType
          extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(contentType) ?: "png"
        }

        val inputStream: InputStream = connection.inputStream
        val tempFile = File.createTempFile("downloaded_image", ".$extension", reactApplicationContext.cacheDir)
        tempFile.outputStream().use { outputStream ->
          inputStream.copyTo(outputStream)
        }
        inputStream.close()
        connection.disconnect()

        tempFile
      }
    }
  }

  private fun saveImage(bitmap: Bitmap, fileName: String): String {
    val updatedFileName = if (fileName.endsWith(".jpg", ignoreCase = true)) {
      fileName.replace(".jpg", ".png", true)
    } else {
      fileName
    }
    val file = File(reactApplicationContext.filesDir, updatedFileName)

    val safeBitmap = if (!bitmap.hasAlpha()) {
      val newBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(newBitmap)
      canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
      canvas.drawBitmap(bitmap, 0f, 0f, null)
      newBitmap
    } else {
      bitmap
    }

    val fileOutputStream = FileOutputStream(file)
    safeBitmap.compress(Bitmap.CompressFormat.PNG, 100, fileOutputStream)
    fileOutputStream.flush()
    fileOutputStream.close()

    return "file://${file.absolutePath}"
  }

  companion object {
    const val NAME = "BackgroundRemover"
  }
}

package com.canhub.cropper

import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import androidx.core.net.toUri
import com.canhub.cropper.CropImageView.CropResult
import com.canhub.cropper.CropImageView.OnCropImageCompleteListener
import com.canhub.cropper.CropImageView.OnSetImageUriCompleteListener
import com.canhub.cropper.databinding.CropImageActivityBinding
import com.canhub.cropper.utils.getUriForFile
import java.io.File

@Deprecated(
  message = """
  Create your own Activity and use the CropImageView directly.
  This way you can customize everything and have utter control of everything.
  Feel free to use this Activity Code to create your own Activity.
""",
)
open class CropImageActivity :
  AppCompatActivity(),
  OnSetImageUriCompleteListener,
  OnCropImageCompleteListener {

  /** Persist URI image to crop URI if specific permissions are required. */
  private var cropImageUri: Uri? = null

  /** The options that were set for the crop image*/
  private lateinit var cropImageOptions: CropImageOptions

  /** The crop image view library widget used in the activity. */
  private var cropImageView: CropImageView? = null
  private lateinit var binding: CropImageActivityBinding
  private var latestTmpUri: Uri? = null
  private val pickImageGallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    onPickImageResult(uri)
  }

  private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) {
    if (it) {
      onPickImageResult(latestTmpUri)
    } else {
      onPickImageResult(null)
    }
  }

  public override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    binding = CropImageActivityBinding.inflate(layoutInflater)
    setContentView(binding.root)
    setCropImageView(binding.cropImageView)
    val bundle = intent.getBundleExtra(CropImage.CROP_IMAGE_EXTRA_BUNDLE)
    cropImageUri = bundle?.parcelable(CropImage.CROP_IMAGE_EXTRA_SOURCE)
    cropImageOptions =
      bundle?.parcelable(CropImage.CROP_IMAGE_EXTRA_OPTIONS) ?: CropImageOptions()

    // --- Новый UI: кнопки внизу ---
    binding.root.findViewById<ImageButton>(R.id.btnClose)?.setOnClickListener {
        setResultCancel()
    }
    binding.root.findViewById<ImageButton>(R.id.btnCrop)?.setOnClickListener {
        cropImage()
    }
    binding.root.findViewById<ImageButton>(R.id.btnRotateRight)?.setOnClickListener {
        rotateImage(cropImageOptions.rotationDegrees)
    }
    binding.root.findViewById<ImageButton>(R.id.btnFlipH)?.setOnClickListener {
        cropImageView?.flipImageHorizontally()
    }
    binding.root.findViewById<ImageButton>(R.id.btnFlipV)?.setOnClickListener {
        cropImageView?.flipImageVertically()
    }

    if (savedInstanceState == null) {
      if (cropImageUri == null || cropImageUri == Uri.EMPTY) {
        when {
          cropImageOptions.showIntentChooser -> showIntentChooser()
          cropImageOptions.imageSourceIncludeGallery &&
            cropImageOptions.imageSourceIncludeCamera ->
            showImageSourceDialog(::openSource)
          cropImageOptions.imageSourceIncludeGallery ->
            pickImageGallery.launch("image/*")
          cropImageOptions.imageSourceIncludeCamera ->
            openCamera()
          else -> finish()
        }
      } else {
        cropImageView?.setImageUriAsync(cropImageUri)
      }
    } else {
      latestTmpUri = savedInstanceState.getString(BUNDLE_KEY_TMP_URI)?.toUri()
    }

    cropImageOptions.activityBackgroundColor.let { activityBackgroundColor ->
      binding.root.setBackgroundColor(activityBackgroundColor)
    }

    // Убираем тулбар и меню
    // supportActionBar?.hide() // если был тулбар

    onBackPressedDispatcher.addCallback {
      setResultCancel()
    }
  }

  private fun setCustomizations() {
    cropImageOptions.activityBackgroundColor.let { activityBackgroundColor ->
      binding.root.setBackgroundColor(activityBackgroundColor)
    }

    supportActionBar?.let {
      title = cropImageOptions.activityTitle.ifEmpty { "" }
      it.setDisplayHomeAsUpEnabled(true)
      cropImageOptions.toolbarColor?.let { toolbarColor ->
        it.setBackgroundDrawable(ColorDrawable(toolbarColor))
      }
      cropImageOptions.toolbarTitleColor?.let { toolbarTitleColor ->
        val spannableTitle: Spannable = SpannableString(title)
        spannableTitle.setSpan(
          ForegroundColorSpan(toolbarTitleColor),
          0,
          spannableTitle.length,
          Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        title = spannableTitle
      }
      cropImageOptions.toolbarBackButtonColor?.let { backBtnColor ->
        try {
          val upArrow = ContextCompat.getDrawable(
            this,
            R.drawable.ic_arrow_back_24,
          )
          upArrow?.colorFilter = PorterDuffColorFilter(backBtnColor, PorterDuff.Mode.SRC_ATOP)
          it.setHomeAsUpIndicator(upArrow)
        } catch (e: Exception) {
          e.printStackTrace()
        }
      }
    }
  }

  private fun showIntentChooser() {
    val ciIntentChooser = CropImageIntentChooser(
      activity = this,
      callback = object : CropImageIntentChooser.ResultCallback {
        override fun onSuccess(uri: Uri?) {
          onPickImageResult(uri)
        }

        override fun onCancelled() {
          setResultCancel()
        }
      },
    )
    cropImageOptions.let { options ->
      options.intentChooserTitle
        ?.takeIf { title ->
          title.isNotBlank()
        }
        ?.let { icTitle ->
          ciIntentChooser.setIntentChooserTitle(icTitle)
        }
      options.intentChooserPriorityList
        ?.takeIf { appPriorityList -> appPriorityList.isNotEmpty() }
        ?.let { appsList ->
          ciIntentChooser.setupPriorityAppsList(appsList)
        }
      val cameraUri: Uri? = if (options.imageSourceIncludeCamera) getTmpFileUri() else null
      ciIntentChooser.showChooserIntent(
        includeCamera = options.imageSourceIncludeCamera,
        includeGallery = options.imageSourceIncludeGallery,
        cameraImgUri = cameraUri,
      )
    }
  }

  private fun openSource(source: Source) {
    when (source) {
      Source.CAMERA -> openCamera()
      Source.GALLERY -> pickImageGallery.launch("image/*")
    }
  }

  private fun openCamera() {
    getTmpFileUri().let { uri ->
      latestTmpUri = uri
      takePicture.launch(uri)
    }
  }

  private fun getTmpFileUri(): Uri {
    val tmpFile = File.createTempFile("tmp_image_file", ".png", cacheDir).apply {
      createNewFile()
      deleteOnExit()
    }

    return getUriForFile(this, tmpFile)
  }

  /**
   * This method show the dialog for user source choice, it is an open function so can be overridden
   * and customised with the app layout if you need.
   */
  open fun showImageSourceDialog(openSource: (Source) -> Unit) {
    AlertDialog.Builder(this)
      .setCancelable(false)
      .setOnKeyListener { _, keyCode, keyEvent ->
        if (keyCode == KeyEvent.KEYCODE_BACK && keyEvent.action == KeyEvent.ACTION_UP) {
          setResultCancel()
          finish()
        }
        true
      }
      .setTitle(R.string.pick_image_chooser_title)
      .setItems(
        arrayOf(
          getString(R.string.pick_image_camera),
          getString(R.string.pick_image_gallery),
        ),
      ) { _, position -> openSource(if (position == 0) Source.CAMERA else Source.GALLERY) }
      .show()
  }

  public override fun onStart() {
    super.onStart()
    cropImageView?.setOnSetImageUriCompleteListener(this)
    cropImageView?.setOnCropImageCompleteListener(this)
  }

  public override fun onStop() {
    super.onStop()
    cropImageView?.setOnSetImageUriCompleteListener(null)
    cropImageView?.setOnCropImageCompleteListener(null)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putString(BUNDLE_KEY_TMP_URI, latestTmpUri.toString())
  }

  protected open fun onPickImageResult(resultUri: Uri?) {
    when (resultUri) {
      null -> setResultCancel()
      else -> {
        cropImageUri = resultUri
        cropImageView?.setImageUriAsync(cropImageUri)
      }
    }
  }

  override fun onSetImageUriComplete(view: CropImageView, uri: Uri, error: Exception?) {
    if (error == null) {
      if (cropImageOptions.initialCropWindowRectangle != null) {
        cropImageView?.cropRect = cropImageOptions.initialCropWindowRectangle
      }

      if (cropImageOptions.initialRotation > 0) {
        cropImageView?.rotatedDegrees = cropImageOptions.initialRotation
      }

      if (cropImageOptions.skipEditing) {
        cropImage()
      }
    } else {
      setResult(null, error, 1)
    }
  }

  override fun onCropImageComplete(view: CropImageView, result: CropResult) {
    setResult(result.uriContent, result.error, result.sampleSize)
  }

  /**
   * Execute crop image and save the result tou output uri.
   */
  open fun cropImage() {
    if (cropImageOptions.noOutputImage) {
      setResult(null, null, 1)
    } else {
      cropImageView?.croppedImageAsync(
        saveCompressFormat = cropImageOptions.outputCompressFormat,
        saveCompressQuality = cropImageOptions.outputCompressQuality,
        reqWidth = cropImageOptions.outputRequestWidth,
        reqHeight = cropImageOptions.outputRequestHeight,
        options = cropImageOptions.outputRequestSizeOptions,
        customOutputUri = cropImageOptions.customOutputUri,
      )
    }
  }

  /**
   * When extending this activity, please set your own ImageCropView
   */
  open fun setCropImageView(cropImageView: CropImageView) {
    this.cropImageView = cropImageView
  }

  /**
   * Rotate the image in the crop image view.
   */
  open fun rotateImage(degrees: Int) {
    cropImageView?.rotateImage(degrees)
  }

  /**
   * Result with cropped image data or error if failed.
   */
  open fun setResult(uri: Uri?, error: Exception?, sampleSize: Int) {
    setResult(
      error?.let { CropImage.CROP_IMAGE_ACTIVITY_RESULT_ERROR_CODE } ?: RESULT_OK,
      getResultIntent(uri, error, sampleSize),
    )
    finish()
  }

  /**
   * Cancel of cropping activity.
   */
  open fun setResultCancel() {
    setResult(RESULT_CANCELED)
    finish()
  }

  /**
   * Get intent instance to be used for the result of this activity.
   */
  open fun getResultIntent(uri: Uri?, error: Exception?, sampleSize: Int): Intent {
    val result = CropImage.ActivityResult(
      originalUri = cropImageView?.imageUri,
      uriContent = uri,
      error = error,
      cropPoints = cropImageView?.cropPoints,
      cropRect = cropImageView?.cropRect,
      rotation = cropImageView?.rotatedDegrees ?: 0,
      wholeImageRect = cropImageView?.wholeImageRect,
      sampleSize = sampleSize,
    )
    val intent = Intent()
    intent.extras?.let(intent::putExtras)
    intent.putExtra(CropImage.CROP_IMAGE_EXTRA_RESULT, result)
    return intent
  }

  enum class Source { CAMERA, GALLERY }

  private companion object {

    const val BUNDLE_KEY_TMP_URI = "bundle_key_tmp_uri"
  }
}

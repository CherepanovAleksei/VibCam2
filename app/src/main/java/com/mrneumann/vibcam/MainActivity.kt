package com.mrneumann.vibcam

import android.Manifest.permission.CAMERA
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.ImageFormat.YUV_420_888
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.SensorManager.SENSOR_DELAY_NORMAL
import android.hardware.camera2.*
import android.hardware.camera2.CameraCharacteristics.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.media.ImageReader.newInstance
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.preference.PreferenceActivity
import android.preference.PreferenceManager
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat.checkSelfPermission
import android.support.v4.content.PermissionChecker.PERMISSION_DENIED
import android.support.v4.content.PermissionChecker.PERMISSION_GRANTED
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import android.widget.Toast.makeText
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    val tagVC = "VibCam"
    //listeners
    private var mSurfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
            openCamera(width, height)
//            previewWindow.surfaceTextureListener = null
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture?, width: Int, height: Int) {
            configureTransform(width,height)
        }

        override fun onSurfaceTextureUpdated(p0: SurfaceTexture?) = Unit

        override fun onSurfaceTextureDestroyed(p0: SurfaceTexture?): Boolean {
            return false
        }
    }
    private val mCameraDeviceStateCallback = object : CameraDevice.StateCallback() {
        override fun onDisconnected(camera: CameraDevice?) {
            camera?.close()
            mCameraDevice = null
        }

        override fun onError(camera: CameraDevice?, error: Int) {
            camera?.close()
            mCameraDevice = null
            this@MainActivity.finish()
        }

        override fun onOpened(cameraDevice: CameraDevice?) {
            mCameraDevice = cameraDevice
            startPreview()
            configureTransform(previewWindow.width,previewWindow.height)
        }
    }
    var mPreviewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession?, request: CaptureRequest?, result: TotalCaptureResult) {
            val newState: Int = result.get(CaptureResult.CONTROL_AF_STATE)
            if (newState != afState) {
                afState = newState
                when (newState) {
                    CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN ->
                        drawRectangle(previewWindow.width / 2, previewWindow.height / 2, Color.RED)
                    CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED -> {
                        changeColor(Color.GREEN)
                        Handler().postDelayed({
                            drawingView.setHaveTouch(Rect(0, 0, 0, 0), Color.TRANSPARENT)
                            drawingView.invalidate()
                        }, 1000)
                    }
                }
            }
        }
    }
    private val mOnVideoAvailableListener = OnImageAvailableListener { imageReader ->
        stabilisation(imageReader.acquireLatestImage())
    }
    //camera
    private var mCameraDevice: CameraDevice? = null
    private lateinit var mCameraId: String
    private var lensFacing = LENS_FACING_BACK
    private lateinit var previewWindow: AutoFitTextureView
    //permission
    private var PERMISSION_REQUEST = 0
    //record
    private var mIsRecording = false
    private var mCaptureSession: CameraCaptureSession? = null
    private var mCaptureRequestBuilder: CaptureRequest.Builder? = null
    var previewBuilder: CaptureRequest? = null
    private var mVideoReader: ImageReader? = null
    var mVideoFileName: String? = null
    lateinit var mVideoFolder: File
    //TapToFocus
    private lateinit var drawingView: DrawingView
    var focusReadyToChange: Boolean = true
    private var focusCounter: Int = 0
    var afState: Int = 0
    //resolution and sizes
    private var resolutionChanged: Boolean = false
    private lateinit var previewSize: Size
    private lateinit var videoSize: Size
    //preferences flags
    private var fpsCounterFlag = false
    private var gyroscopeFlag = false
    private var accelerometerFlag = false
    private var resolutionPreference = 0
    //fps
    private var fps: Int = 0
    private val frameCounter = Handler()
    private var runnable = object : Runnable {
        override fun run() {
            fpsCounter.text = fps.toString()
            fps = 0
            frameCounter.postDelayed(this, 1000)
        }
    }
    //accelerometer
    private lateinit var mSensorManager: SensorManager
    var mAccelerometerArr: FloatArray = FloatArray(3)
    private var mAccelerometer: Sensor? = null
    private var mAccelerometerListener:SensorEventListener? = null
    //gyroscope
    var mGyroscopeArr: FloatArray = FloatArray(3)
    private var mGyroscope: Sensor? = null
    private var mGyroscopeListener:SensorEventListener? = null
    //Orientation
    private lateinit var mOrientationSensorManager: SensorManager
    private var mRotateAccelerometer: Sensor? = null
    private var mRotateAccelerometerListener:SensorEventListener? = null
    var orientation: Float = 0F

    //Activities
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(this@MainActivity, arrayOf(CAMERA, WRITE_EXTERNAL_STORAGE), PERMISSION_REQUEST)
        }
        drawingView = this.window.decorView.findViewById<View>(android.R.id.content).findViewById(R.id.drawing_view)
        previewWindow = this.window.decorView.findViewById<View>(android.R.id.content).findViewById(R.id.texture)

        mOrientationSensorManager = getSystemService(Service.SENSOR_SERVICE) as SensorManager

        recordButton.setOnClickListener {
            if (mIsRecording) {
                stopRecord()
                startPreview()
            } else {
                mIsRecording = true
                makeInvisible()
                recordButton.isSelected = true
                startRecord()
            }
        }
        settingsButton.setOnClickListener {
            closeCamera()
            val intent: Intent = Intent(this@MainActivity,SettingsActivity::class.java).apply {
                putExtra(
                        PreferenceActivity.EXTRA_SHOW_FRAGMENT,
                        SettingsActivity.GeneralPreferenceFragment::class.java.name
                )
                putExtra(PreferenceActivity.EXTRA_NO_HEADERS,true)

            }
            startActivity(intent)
        }
        lensFacingButton.isSelected = true
        lensFacingButton.setOnClickListener {
            if(lensFacing == LENS_FACING_BACK) {
                changeColor(Color.TRANSPARENT)
                lensFacing = LENS_FACING_FRONT
                lensFacingButton.isSelected = false
            } else {
                lensFacing = LENS_FACING_BACK
                lensFacingButton.isSelected = true
            }
            closeCamera()
            openCamera(previewWindow.width, previewWindow.height)
        }
        galleryButton.setOnClickListener{
            closeCamera()
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                type = "image/*"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }

    override fun onResume() {
        super.onResume()
        updatePreferences()

        createVideoFolder()
        if (previewWindow.isAvailable) {
            openCamera(previewWindow.width, previewWindow.height)
        } else {
            previewWindow.surfaceTextureListener = mSurfaceTextureListener
        }
    }

    override fun onPause() {
        if (mIsRecording) stopRecord()
        if(accelerometerFlag) mSensorManager.unregisterListener(mAccelerometerListener)
        if(gyroscopeFlag) mSensorManager.unregisterListener(mGyroscopeListener)
        if (mRotateAccelerometerListener != null) {
            mOrientationSensorManager.unregisterListener(mRotateAccelerometerListener)
        }
        closeCamera()
        super.onPause()
    }
//TODO check preview resolution
    //setup camera
    private fun openCamera(width: Int, height: Int) {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (camID in cameraManager.cameraIdList) {
                val cameraCharacteristics = cameraManager.getCameraCharacteristics(camID) as CameraCharacteristics
                //choose camera
                if (cameraCharacteristics.get(LENS_FACING) != lensFacing) continue
                mCameraId = camID

                //resolution
                val map = cameraCharacteristics
                        .get(SCALER_STREAM_CONFIGURATION_MAP)
                videoSize = videoResolution(map.getOutputSizes(ImageReader::class.java))

                mVideoReader = newInstance(
                        videoSize.width,
                        videoSize.height,
                        YUV_420_888,
                        10
                ).apply {
                    setOnImageAvailableListener(mOnVideoAvailableListener, null)
                }
                if (resolutionChanged) {
                    Toast.makeText(this, mVideoReader!!.width.toString() + "x" + mVideoReader!!.height.toString(), LENGTH_SHORT).show()
                    resolutionChanged = false
                }
                previewSize = chooseOptimalSize(
                        map.getOutputSizes(SurfaceTexture::class.java),
                        width,
                        height,
                        videoSize
                )
                previewWindow.setAspectRatio(previewSize.height, previewSize.width)
                Log.d(tagVC, "prewiew window size: h:" + previewSize.height.toString() + " w:" + previewSize.width.toString())
                configureTransform(width,height)

                if (checkSelfPermission(this@MainActivity, CAMERA) == PERMISSION_GRANTED) {
                    cameraManager.openCamera(camID, mCameraDeviceStateCallback, null)
                }
            }
        } catch (e: CameraAccessException) {
            Log.e(tagVC, "Cannot access the camera.")
            this.finish()
        } catch (e: NullPointerException) {
            Log.e(tagVC, "NullPointerException in openCamera")

        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.")
        }
    }

    private fun startPreview() {
        val surfaceTexture: SurfaceTexture = previewWindow.surfaceTexture
        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(surfaceTexture)

        try {
            stopPreview()
            rotationStart()
            mCaptureRequestBuilder = mCameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            mCaptureRequestBuilder?.apply {
                addTarget(previewSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            previewBuilder = mCaptureRequestBuilder?.build()
            mCameraDevice?.createCaptureSession(Arrays.asList(previewSurface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            Log.d(tagVC, "onConfigured: startPreview")
                            mCaptureSession = session
                            try {
                                mCaptureSession?.setRepeatingRequest(
                                        previewBuilder,
                                        mPreviewCallback,
                                        null)
                            } catch (e: CameraAccessException) {
                                e.printStackTrace()
                            }
                        }

                        override fun onConfigureFailed(p0: CameraCaptureSession?) {
                            Log.d(tagVC, "onConfigureFailed: startPreview")
                        }
                    },
                    null)
            setTapToFocus()
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    //record
    private fun startRecord() = try {
        //fps count start
        if(fpsCounterFlag) {
            frameCounter.postDelayed(runnable, 1000)
        }
        stopPreview()
        createVideoFileName()

        val surfaceTexture: SurfaceTexture = previewWindow.surfaceTexture.apply {
            setDefaultBufferSize(previewSize.width,previewSize.height)
        }
        val previewSurface = Surface(surfaceTexture)
        val recordSurface: Surface = mVideoReader!!.surface

        mCaptureRequestBuilder = mCameraDevice!!
                .createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                .apply {
                    addTarget(previewSurface)
                    addTarget(recordSurface)
                }

        //lock focus
        mCaptureRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        changeColor(Color.TRANSPARENT)

        mCameraDevice!!.createCaptureSession(
                Arrays.asList(recordSurface, previewSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        mCaptureSession = cameraCaptureSession
                        try {
                            mCaptureSession?.setRepeatingRequest(
                                    mCaptureRequestBuilder?.build(),
                                    null,
                                    null)
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession?) {
                        Log.d(tagVC, "onConfigureFailed in startRecord()")
                    }
                }, null)
    } catch (e: CameraAccessException) {
        Log.e(tagVC, e.toString())
    } catch (e: IOException) {
        Log.e(tagVC, e.toString())
    }

    //setup
    private fun createVideoFolder() {
        val videoFile: File = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        mVideoFolder = File(videoFile, "VibCam")
        if (!mVideoFolder.exists()) {
            mVideoFolder.mkdirs()
        }
    }

    //TODO
    @SuppressLint("SimpleDateFormat")
    fun createVideoFileName() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val prepend = "VIDEO_$timestamp"
        val videoFile = File.createTempFile(prepend, ".mp4", mVideoFolder)
        mVideoFileName = videoFile.absolutePath
    }

    private fun makeInvisible(){
        galleryButton.visibility = View.INVISIBLE
        settingsButton.visibility = View.INVISIBLE
        lensFacingButton.visibility = View.INVISIBLE
    }

    private fun makeVisible(){
        galleryButton.visibility = View.VISIBLE
        settingsButton.visibility = View.VISIBLE
        lensFacingButton.visibility = View.VISIBLE
    }

    //resolution and sizes
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val rotation = this.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = Math.max(
                    viewHeight.toFloat() / previewSize.height,
                    viewWidth.toFloat() / previewSize.width)
            with(matrix) {
                postScale(scale, scale, centerX, centerY)
                postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            }
        }
        previewWindow.setTransform(matrix)
    }

    private fun chooseOptimalSize(
            choices: Array<Size>,
            width: Int,
            height: Int,
            aspectRatio: Size
    ): Size {

        // Collect the supported resolutions that are at least as big as the preview Surface
        val w = aspectRatio.width
        val h = aspectRatio.height
        val bigEnough = choices.filter {
            it.height == it.width * h / w && it.width >= width && it.height >= height }

        // Pick the smallest of those, assuming we found any
        return if (bigEnough.isNotEmpty()) {
            Collections.min(bigEnough, CompareSizesByArea())
        } else {
            choices[0]
        }
    }

    private fun videoResolution(arr:Array<Size>):Size{
        when(resolutionPreference){
            4 -> {
                for (item in arr) if(item.width / item.height == 4/3 && item.width <= 1920) return item
                return Size(1920,1080)
            }
            3 -> {
                for (item in arr) if(item.width / item.height == 4/3 && item.width <= 960) return item
                return Size(960,720)
            }
            2 -> {
                for (item in arr) if(item.width / item.height == 4/3 && item.width <= 720) return item
                return Size(720,540)
            }
            1 -> {
                for (item in arr) if(item.width / item.height == 4/3 && item.width <= 640) return item
                return Size(640,480)
            }
            else -> {
                for (item in arr) if(item.width / item.height == 4/3 && item.width <= 480) return item
                return Size(480,360)
            }
        }
    }

    //close
    private fun stopPreview() {
        rotationLock()
//        mCaptureSession?.stopRepeating()
        mCaptureSession?.close()
        mCaptureSession = null
    }

    private fun stopRecord() {
        //fps stop
        if(fpsCounterFlag) {
            frameCounter.removeCallbacks(runnable)
            fps = 0
            fpsCounter.text = getString(R.string.fps_name)
        }

        //Buttons
        mIsRecording = false
        makeVisible()
        recordButton.isSelected = false

        //restart AF
        mCaptureSession?.stopRepeating()
        mCaptureRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
        mCaptureSession?.capture(mCaptureRequestBuilder?.build(), mPreviewCallback, null)

        mCaptureSession?.stopRepeating()
        mCaptureSession?.close()
        mCaptureSession = null

        updateGallery()
        finishWithResult()
    }

    private fun closeCamera() {
        try {
            if (mIsRecording) stopRecord()
            stopPreview()
            mCameraDevice?.close()
            mCameraDevice = null
            mVideoReader?.close()
            mVideoReader = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Can't close camera", e)
        } catch (e:UninitializedPropertyAccessException){
            e.printStackTrace()
        }

    }

    //permissions
    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<String>,
            grantResults: IntArray
    ) {
        when (requestCode) {
            PERMISSION_REQUEST -> {
                if (grantResults.isEmpty()
                        || grantResults[0] == PERMISSION_DENIED
                        || grantResults[1] == PERMISSION_DENIED) {
                    makeText(this@MainActivity, "I can't work without it!", LENGTH_SHORT).show()
                    this@MainActivity.finish()
                }
                return
            }
        }
    }

    //preferences
    private fun updatePreferences(){
        //resolution
        val newResolutionPreference = PreferenceManager
                .getDefaultSharedPreferences(this)
                .getString("resolution_list", "0")
                .toInt()
        if (newResolutionPreference != resolutionPreference) {
            resolutionPreference = newResolutionPreference
            resolutionChanged = true
        }
        //fps
        fpsCounterFlag = PreferenceManager
                .getDefaultSharedPreferences(this)
                .getBoolean("fps_counter_switch", true)
        if(fpsCounterFlag){
            fpsCounter.text = getString(R.string.fps_name)
        } else{
            fpsCounter.text = ""
        }

        //accelerometer and gyroscope
        mSensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        gyroscopeFlag = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("gyroscope_switch", false)
        if(gyroscopeFlag){
            mGyroscope = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            if(mGyroscope == null) {
                Toast.makeText(this@MainActivity, "I need gyroscope", Toast.LENGTH_SHORT).show()
                finish()
            }
            mGyroscopeListener = object : SensorEventListener {
                override fun onSensorChanged(sensorEvent: SensorEvent) {
                    val mySensor = sensorEvent.sensor as Sensor
                    if (mySensor.type == Sensor.TYPE_GYROSCOPE) {
                        System.arraycopy(sensorEvent.values, 0, mGyroscopeArr, 0, sensorEvent.values.size)
                    }
                }
                override fun onAccuracyChanged(p0: Sensor?, p1: Int) {}
            }
            mSensorManager.registerListener(mGyroscopeListener, mGyroscope, SENSOR_DELAY_NORMAL)
        }else{
            if(mGyroscopeListener != null) mSensorManager.unregisterListener(mGyroscopeListener)
            mGyroscopeListener = null
        }
        accelerometerFlag = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("accelerometer_switch",false)
        if(accelerometerFlag){
            mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if(mAccelerometer == null){
                Toast.makeText(this@MainActivity, "I need accelerometer",Toast.LENGTH_SHORT).show()
                finish()
            }
            mAccelerometerListener = object : SensorEventListener {
                override fun onSensorChanged(sensorEvent: SensorEvent) {
                    val mySensor = sensorEvent.sensor as Sensor
                    if (mySensor.type == Sensor.TYPE_ACCELEROMETER) {
                        System.arraycopy(sensorEvent.values, 0, mAccelerometerArr, 0, sensorEvent.values.size)
                    }
                }

                override fun onAccuracyChanged(p0: Sensor?, p1: Int) {}
            }
            mSensorManager.registerListener(mAccelerometerListener, mAccelerometer, SENSOR_DELAY_NORMAL)
        } else{
            if(mAccelerometerListener != null) mSensorManager.unregisterListener(mAccelerometerListener)
            mAccelerometerListener = null
        }
    }

    //stabilisation
    private fun stabilisation(image: Image?) {
        if (null == image) return
        if(fpsCounterFlag) {
            fps++
        }
        image.close()
        return
    }

    //TapToFocus
    private fun setTapToFocus(){
        restartFocus()
        previewWindow.setOnTouchListener { view, motionEvent ->
            if (motionEvent.actionMasked != MotionEvent.ACTION_DOWN) {
                return@setOnTouchListener false
            }
            if (lensFacing != CameraMetadata.LENS_FACING_BACK) return@setOnTouchListener true
            if (!focusReadyToChange) {
                Log.d(tagVC, "ManualFocus have already Engaged")
                return@setOnTouchListener true
            }
            if (!mIsRecording) lockFocus(view, motionEvent)

            return@setOnTouchListener true
        }
    }

    private fun lockFocus(view: View, motionEvent: MotionEvent) {
        try {
            focusReadyToChange = false
            drawRectangle(motionEvent.x.toInt(), motionEvent.y.toInt(), Color.RED)
            focusCounter++

            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val sensorArraySize:Rect = cameraManager
                    .getCameraCharacteristics(mCameraId)
                    .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

            val y:Int = sensorArraySize.height() - ((motionEvent.x / view.width.toFloat())  * sensorArraySize.height().toFloat()).toInt()
            val x:Int = ((motionEvent.y / view.height.toFloat()) * sensorArraySize.width().toFloat()).toInt()

            val halfTouchWidth  = 100
            val halfTouchHeight = 100

            val touchArea = MeteringRectangle(
                    Math.max(x - halfTouchWidth,  0),
                    Math.max(y - halfTouchHeight, 0),
                    halfTouchWidth  * 2,
                    halfTouchHeight * 2,
                    MeteringRectangle.METERING_WEIGHT_MAX)

            val captureCallbackHandler:CameraCaptureSession.CaptureCallback = object: CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession?, request: CaptureRequest?, result: TotalCaptureResult?) {
                    //                super.onCaptureCompleted(session, request, result)

                    if (request?.tag == "FOCUS_TAG") {
                        Log.d(tagVC, "FOCUS_TAG")
                        //the focus trigger is complete -
                        //resume repeating (preview surface will get frames), clear AF trigger
                        mCaptureRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, null)
                        mCaptureSession?.setRepeatingRequest(
                                mCaptureRequestBuilder?.build(),
                                object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(session: CameraCaptureSession?, request: CaptureRequest?, result: TotalCaptureResult) {
                                        val newState: Int = result.get(CaptureResult.CONTROL_AF_STATE)
                                        if (newState != afState && newState == CONTROL_AF_STATE_ACTIVE_SCAN) {
                                            afState = newState
                                        } else if (newState != afState && newState == CONTROL_AF_STATE_FOCUSED_LOCKED) {
                                            afState = newState
                                            changeColor(Color.GREEN)
                                            val currentFocusCount = focusCounter
                                            Handler().apply {
                                                postDelayed({
                                                    if (currentFocusCount == focusCounter) {
                                                        changeColor(Color.TRANSPARENT)
                                                    }
                                                }, 1000)
                                                postDelayed({
                                                    if (currentFocusCount == focusCounter && !mIsRecording) {
                                                        restartFocus()
                                                    }
                                                }, 4000)
                                            }
                                        }
                                    }
                                },
                                null)
                    }
                    focusReadyToChange = true
                }

                override fun onCaptureFailed(session: CameraCaptureSession?, request: CaptureRequest?, failure: CaptureFailure?) {
                    super.onCaptureFailed(session, request, failure)
                    Log.e(tagVC, "Manual AF failure: $failure")
                    focusReadyToChange = true
                }
            }
            //first stop the existing repeating request
            mCaptureSession?.stopRepeating()

            //cancel any existing AF trigger (repeated touches, etc.)
            mCaptureRequestBuilder?.apply {
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            }
            mCaptureSession?.capture(mCaptureRequestBuilder?.build(), captureCallbackHandler, null)

            //Now add a new AF trigger with focus region
            if (isMeteringAreaAFSupported()) {
                mCaptureRequestBuilder?.apply {
                    set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(touchArea))
                    set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(touchArea))
                    set(CaptureRequest.CONTROL_AWB_REGIONS, arrayOf(touchArea))
                }
            }
            mCaptureRequestBuilder?.apply {
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                setTag("FOCUS_TAG") //we'll capture this later for resuming the preview
            }
            mCaptureSession?.capture(mCaptureRequestBuilder?.build(), captureCallbackHandler, null)
        } catch (e: CameraAccessException) {
            Log.e(tagVC, e.toString())
        }
    }
    private fun isMeteringAreaAFSupported():Boolean {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return cameraManager
                .getCameraCharacteristics(mCameraId)
                .get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) >= 1
    }

    private fun restartFocus() {
        if (focusReadyToChange) {
            mCaptureSession?.stopRepeating()
            mCaptureRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            mCaptureSession?.capture(mCaptureRequestBuilder?.build(), mPreviewCallback, null)
            mCaptureSession?.setRepeatingRequest(previewBuilder, mPreviewCallback, null)
        }
    }

    //draw Rectangle
    private fun drawRectangle(x: Int, y: Int, color: Int) {
        drawingView.apply {
            setHaveTouch(
                    Rect(
                            x - 100,
                            y - 100 + drawingView.height - previewWindow.height,
                            x + 100,
                            y + 100 + drawingView.height - previewWindow.height),
                    color)
            invalidate()
        }
    }

    private fun changeColor(color: Int) {
        drawingView.apply {
            setColor(color)
            invalidate()
        }
    }

    //rotation
    private fun rotationStart() {
        mRotateAccelerometerListener = object : SensorEventListener {
            override fun onSensorChanged(sensorEvent: SensorEvent) {
                val mySensor = sensorEvent.sensor as Sensor
                if (mySensor.type == Sensor.TYPE_ACCELEROMETER) {
                    val x: Float = sensorEvent.values[0]
                    val y: Float = sensorEvent.values[1]
                    var newOrientation: Float? = null
                    //vertical
                    if (y > 7 && x < 5 && x > -5 && orientation != 0F) {
                        newOrientation = 0F
                    }
                    //landscape_left
                    else if (x > 5 && y < 5 && orientation != 90F) {
                        newOrientation = 90F
                    }

//                    //reverse_vertical
//                    else if (y < -7 && x < 5 && x > -5 && orientation != 180F) {
//                        newOrientation = 180F
//                    }
                    //landscape_right
                    else if (x < -5 && y < 5 && orientation != -90F) {
                        newOrientation = -90F
                    }
                    if (newOrientation != null) {
                        val deg: Float = newOrientation
                        for (imageButton in listOf(recordButton, settingsButton, lensFacingButton, galleryButton))
                            imageButton.apply {
                                animate().rotation(deg).interpolator = AccelerateDecelerateInterpolator()
                            }
                        orientation = newOrientation
                    }
                }
            }

            override fun onAccuracyChanged(p0: Sensor?, p1: Int) {}
        }

        mRotateAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        mOrientationSensorManager.registerListener(mRotateAccelerometerListener, mRotateAccelerometer, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun rotationLock() {
        if (mRotateAccelerometerListener != null)
            mOrientationSensorManager.unregisterListener(mRotateAccelerometerListener)
        mRotateAccelerometerListener = null
        mRotateAccelerometer = null
    }

    //Integration
    private fun finishWithResult() {
        if (callingActivity != null) {
            closeCamera()
            val sendVideoIntent = Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE)
            sendVideoIntent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, Uri.fromFile(File(mVideoFileName)))
            setResult(Activity.RESULT_OK, sendVideoIntent)
            sendBroadcast(sendVideoIntent)
            finish()
        }
    }

    private fun updateGallery() {
        val mediaStoreUpdateIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
        mediaStoreUpdateIntent.data = Uri.fromFile(File(mVideoFileName))
        sendBroadcast(mediaStoreUpdateIntent)
    }
}
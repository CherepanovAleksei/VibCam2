package com.mrneumann.vibcam

import android.Manifest.permission.*
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.graphics.ImageFormat.YUV_420_888
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.SensorManager.SENSOR_DELAY_NORMAL
import android.hardware.camera2.*
import android.hardware.camera2.CameraCharacteristics.*
import android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
import android.media.Image
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.media.ImageReader.newInstance
import android.support.v7.app.AppCompatActivity
import android.support.v4.content.ContextCompat.checkSelfPermission
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.preference.PreferenceActivity
import android.preference.PreferenceManager
import android.support.v4.app.ActivityCompat
import android.support.v4.app.ActivityCompat.shouldShowRequestPermissionRationale
import android.support.v4.content.PermissionChecker.PERMISSION_GRANTED
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import android.widget.Toast.*
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
class MainActivity : AppCompatActivity() {

    val TAG = "VibCam"
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
    private val mOnVideoAvailableListener = OnImageAvailableListener { imageReader ->
        stabilisation(imageReader.acquireLatestImage())
    }
    //camera
    private var mPreviewCaptureSession: CameraCaptureSession? = null
    private var mCameraDevice: CameraDevice? = null
    private lateinit var mCameraId: String
    private var lensFacing = LENS_FACING_BACK
    private lateinit var previewWindow: AutoFitTextureView
    //permission
    private var CAMERA_PERMISSION_REQUEST = 0
    private var STORAGE_PERMISSION_REQUEST = 0
    //record
    private var mIsRecording = false
    private var mRecordCaptureSession: CameraCaptureSession? = null
    private lateinit var mCaptureRequestBuilder: CaptureRequest.Builder
    private lateinit var mVideoReader: ImageReader
    var mVideoFileName: String? = null
    lateinit var mVideoFolder: File
    //resolution and sizes
    private var sensorOrientation = 0
    lateinit var previewSize:Size
    lateinit var videoSize:Size
    //preferences flags
    private var fpsCounterFlag = false
    private var gyroscopeFlag = false
    private var accelerometerFlag = false
    private var resolutionPreference = -1
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

    //Activities
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        getPermission()

        previewWindow = texture.findViewById(R.id.texture)
        recordButton.setOnClickListener {
            if (mIsRecording) {
                stopRecord()
                makeVisible()
                startPreview()
            } else {
                mIsRecording = true
                makeInvisible()
                recordButton.setBackgroundColor(android.graphics.Color.RED)
                getPermission()
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
        lensFacingButton.setOnClickListener {
            if(lensFacing == LENS_FACING_BACK) lensFacing = LENS_FACING_FRONT
            else lensFacing = LENS_FACING_BACK
            closeCamera()
            openCamera(previewWindow.width, previewWindow.height)
        }
        galleryButton.setOnClickListener{

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
        closeCamera()
        super.onPause()
    }

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
                sensorOrientation = cameraCharacteristics.get(SENSOR_ORIENTATION)
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
                Toast.makeText(this,mVideoReader.width.toString()+"x"+mVideoReader.height.toString(), LENGTH_SHORT).show()

                previewSize = chooseOptimalSize(
                        map.getOutputSizes(SurfaceTexture::class.java),
                        width,
                        height,
                        videoSize
                )


                if(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE){
                    previewWindow.setAspectRatio(previewSize.width,previewSize.height)
                }else{
                    previewWindow.setAspectRatio(previewSize.height,previewSize.width)
                }
                configureTransform(width,height)

                if (checkSelfPermission(this@MainActivity, CAMERA) == PERMISSION_GRANTED) {
                    cameraManager.openCamera(camID, mCameraDeviceStateCallback, null) //mBackgroundHandler
                }
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Cannot access the camera.")
            this.finish()
        } catch (e: NullPointerException) {
            Log.e(TAG, "NullPointerException in openCamera")

        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.")
        }
    }

    private fun startPreview() {
        val surfaceTexture: SurfaceTexture = previewWindow.surfaceTexture
        surfaceTexture.setDefaultBufferSize(640, 480)
        val previewSurface = Surface(surfaceTexture)

        try {
            stopPreview()
            mCaptureRequestBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            mCaptureRequestBuilder.addTarget(previewSurface)
            mCameraDevice!!.createCaptureSession(Arrays.asList(previewSurface/*,mImageReader.surface*/),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            Log.d(TAG, "onConfigured: startPreview")
                            mPreviewCaptureSession = session
                            try {
                                mPreviewCaptureSession?.setRepeatingRequest(mCaptureRequestBuilder.build(), null, null)
                            } catch (e: CameraAccessException) {
                                e.printStackTrace()
                            }
                        }

                        override fun onConfigureFailed(p0: CameraCaptureSession?) {
                            Log.d(TAG, "onConfigureFailed: startPreview")
                        }
                    },
                    null)
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
        val recordSurface: Surface = mVideoReader.surface

        mCaptureRequestBuilder = mCameraDevice!!
                .createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                .apply {
                    addTarget(previewSurface)
                    addTarget(recordSurface)
                }

        mCameraDevice!!.createCaptureSession(
                Arrays.asList(recordSurface, previewSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        mRecordCaptureSession = cameraCaptureSession
                        try {
                            mRecordCaptureSession?.setRepeatingRequest(mCaptureRequestBuilder.build(), null, null)
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession?) {
                        Log.d(TAG, "onConfigureFailed in startRecord()")
                    }
                }, null)
    } catch (e: CameraAccessException) {
        Log.e(TAG, e.toString())
    } catch (e: IOException) {
        Log.e(TAG, e.toString())
    }

    //setup
    fun createVideoFolder() {
        val videoFile: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
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
            1 -> {
                for (item in arr) if(item.width / item.height == 4/3) return item
                return Size(960,720)
            }
            0 -> {
                for (item in arr) if(item.width / item.height == 4/3 && item.width <= 720) return item
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
        mPreviewCaptureSession?.stopRepeating()
        mPreviewCaptureSession?.close()
        mPreviewCaptureSession = null
    }

    private fun stopRecord() {
        //fps stop
        if(fpsCounterFlag) {
            frameCounter.removeCallbacks(runnable)
            fpsCounter.text = getString(R.string.fps_name)
        }

        mIsRecording = false
        recordButton.setBackgroundColor(android.graphics.Color.GREEN)
        mRecordCaptureSession?.stopRepeating()
        mPreviewCaptureSession?.close()
        mRecordCaptureSession = null
    }

    private fun closeCamera() {
        try {
            if (mIsRecording) stopRecord()
            stopPreview()
            mCameraDevice?.close()
            mCameraDevice = null
            mVideoReader.close()
        } catch (e: InterruptedException) {
            throw RuntimeException("Can't close camera", e)
        }
    }

    //permissions
    private fun getPermission() {
        if (checkSelfPermission(this@MainActivity, CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            if (shouldShowRequestPermissionRationale(this@MainActivity, CAMERA)) {
                makeText(this@MainActivity, "I can't work without Camera!", LENGTH_SHORT).show()
            }
            ActivityCompat.requestPermissions(this@MainActivity, arrayOf(CAMERA), CAMERA_PERMISSION_REQUEST)
        }
        if (checkSelfPermission(this@MainActivity, WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            if (shouldShowRequestPermissionRationale(this@MainActivity, WRITE_EXTERNAL_STORAGE)) {
                makeText(this@MainActivity, "I can't work without saving!", LENGTH_SHORT).show()
            }
            ActivityCompat.requestPermissions(this@MainActivity, arrayOf(WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<String>,
            grantResults: IntArray
    ) {
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PERMISSION_GRANTED) {

                } else {
                    makeText(this@MainActivity, "I can't work without Camera!!", LENGTH_SHORT).show()

                }
                return
            }
            STORAGE_PERMISSION_REQUEST -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PERMISSION_GRANTED) {

                } else {
                    makeText(this@MainActivity, "I can't work without saving!", LENGTH_SHORT).show()
                    this@MainActivity.finish()
                }
                return
            }
        }
    }

    //preferences
    private fun updatePreferences(){
        //resolution
        resolutionPreference = PreferenceManager
                .getDefaultSharedPreferences(this)
                .getString("resolution_list", "-1")
                .toInt()
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
    fun stabilisation(image: Image?) {
        if (null == image) return
        if(fpsCounterFlag) {
            fps++
        }
        image.close()
        return
    }
}
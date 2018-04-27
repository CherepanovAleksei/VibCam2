package com.mrneumann.vibcam

import android.Manifest.permission.*
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.support.v7.app.AppCompatActivity
import android.support.v4.content.ContextCompat.checkSelfPermission
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.support.v4.app.ActivityCompat
import android.support.v4.app.ActivityCompat.shouldShowRequestPermissionRationale
import android.support.v4.content.PermissionChecker.PERMISSION_GRANTED
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Toast.*
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    val TAG = "VibCam"
    private var mSurfaceTextureListener = object: TextureView.SurfaceTextureListener{
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
            openCamera(width, height)
//            previewWindow.surfaceTextureListener = null
        }
        override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture?, p1: Int, p2: Int){} //?

        override fun onSurfaceTextureUpdated(p0: SurfaceTexture?) = Unit

        override fun onSurfaceTextureDestroyed(p0: SurfaceTexture?): Boolean{
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
        }
    }
    private lateinit var mCaptureRequestBuilder: CaptureRequest.Builder
    private var mPreviewCaptureSession: CameraCaptureSession? = null
    private var mCameraDevice: CameraDevice? = null
    private lateinit var mCameraId: String
    private var CAMERA_PERMISSION_REQUEST = 0
    private var STORAGE_PERMISSION_REQUEST = 0
    private var mIsRecording = false
    private var mRecordCaptureSession: CameraCaptureSession? = null
    private lateinit var mVideoReader: ImageReader
    private var mVideoFileName: String? = null
    private lateinit var mVideoFolder: File
    private val mOnVideoAvailableListener = ImageReader.OnImageAvailableListener {
        imageReader -> stabilisation(imageReader.acquireLatestImage())
    }
    //accelerometer
    private lateinit var mSensorManager: SensorManager
    var mAccelerometerArr: FloatArray = FloatArray(3)
    private var mAccelerometer: Sensor? = null
    private val mAccelerometerListener = object: SensorEventListener {
        override fun onSensorChanged(sensorEvent: SensorEvent) {
            val mySensor = sensorEvent.sensor as Sensor
            if(mySensor.type == Sensor.TYPE_ACCELEROMETER){
                System.arraycopy(sensorEvent.values,0,mAccelerometerArr, 0, sensorEvent.values.size)
            }
        }
        override fun onAccuracyChanged(p0: Sensor?, p1: Int) {}
    }
    //gyroscope
    var mGyroscopeArr: FloatArray = FloatArray(3)
    private var mGyroscope:Sensor? = null
    private val mGyroscopeListener = object:SensorEventListener{
        override fun onSensorChanged(sensorEvent: SensorEvent) {
            val mySensor = sensorEvent.sensor as Sensor
            if(mySensor.type == Sensor.TYPE_GYROSCOPE){
                System.arraycopy(sensorEvent.values,0,mGyroscopeArr, 0, sensorEvent.values.size)
            }
        }
        override fun onAccuracyChanged(p0: Sensor?, p1: Int) {}
    }

    //Activities
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        getPermission()

        mSensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
//        TODO uncomment!
//        mGyroscope = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
//        mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
//        if(mGyroscope == null){
//            Toast.makeText(this@MainActivity, "I need gyroscope",Toast.LENGTH_SHORT).show()
//            finish()
//        }else if(mAccelerometer == null){
//            Toast.makeText(this@MainActivity, "I need accelerometer",Toast.LENGTH_SHORT).show()
//            finish()
//        }

        recordButton.setOnClickListener {
            if(mIsRecording){
                stopRecord()
                startPreview()
            }else{
                mIsRecording = true
                recordButton.setBackgroundColor(android.graphics.Color.RED)
                getPermission()
                startRecord()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        //gyroscope and accelerometer
        //TODO uncomment!!!
//        mSensorManager.registerListener(mGyroscopeListener, mGyroscope, SENSOR_DELAY_NORMAL)
//        mSensorManager.registerListener(mAccelerometerListener, mAccelerometer, SENSOR_DELAY_NORMAL)

        createVideoFolder()
        if(previewWindow.isAvailable){
            openCamera(previewWindow.width,previewWindow.height)
        }else{
            previewWindow.surfaceTextureListener = mSurfaceTextureListener
        }
    }

    override fun onPause() {
        if(mIsRecording) stopRecord()
        mSensorManager.unregisterListener(mAccelerometerListener)
        mSensorManager.unregisterListener(mGyroscopeListener)
        closeCamera()
        super.onPause()
    }

    private fun openCamera(width: Int, height: Int) {

        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            mVideoReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 10) //hardcode
            mVideoReader.setOnImageAvailableListener(mOnVideoAvailableListener, null)
            for(camID in cameraManager.cameraIdList){
                val cameraCharacteristics = cameraManager.getCameraCharacteristics(camID) as CameraCharacteristics
                //определяем основая ли камера
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) continue
                mCameraId = camID
                if(checkSelfPermission(this@MainActivity, CAMERA) == PERMISSION_GRANTED) {
                    cameraManager.openCamera(camID, mCameraDeviceStateCallback, null) //mBackgroundHandler
                }
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG,"Cannot access the camera.")
            this.finish()
        } catch (e: NullPointerException) {
            Log.e(TAG,"NullPointerException in openCamera")

        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.")
        }
    }
    private fun startPreview(){
        val surfaceTexture: SurfaceTexture = previewWindow.surfaceTexture
        surfaceTexture.setDefaultBufferSize(640, 480)
        val previewSurface = Surface(surfaceTexture)

        try {
            stopPreview()
            mCaptureRequestBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            mCaptureRequestBuilder.addTarget(previewSurface)
            mCameraDevice!!.createCaptureSession(Arrays.asList(previewSurface/*,mImageReader.surface*/),
                    object: CameraCaptureSession.StateCallback(){
                        override fun onConfigured(session: CameraCaptureSession) {
                            Log.d(TAG, "onConfigured: startPreview")
                            mPreviewCaptureSession = session
                            try {
                                mPreviewCaptureSession?.setRepeatingRequest(mCaptureRequestBuilder.build(),null,null)
                            }catch (e: CameraAccessException){
                                e.printStackTrace()
                            }
                        }

                        override fun onConfigureFailed(p0: CameraCaptureSession?) {
                            Log.d(TAG, "onConfigureFailed: startPreview")
                        }
                    },
                    null)
        }catch (e:CameraAccessException){
            e.printStackTrace()
        }
    }

    //fps
    private var fps:Int = 0
    private val frameCounter = Handler()
    private var runnable = object :Runnable{
        override fun run() {
            fpsCounter.text = fps.toString()
            fps = 0
            frameCounter.postDelayed(this, 1000)
        }
    }

    private fun startRecord() = try {
        //fps start
        frameCounter.postDelayed(runnable,1000)

        stopPreview()
        createVideoFileName()

        val surfaceTexture:SurfaceTexture = previewWindow.surfaceTexture.apply {
            setDefaultBufferSize(640,480) //hardcode
        }
        val previewSurface = Surface(surfaceTexture)
        val recordSurface:Surface = mVideoReader.surface

        mCaptureRequestBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        mCaptureRequestBuilder.addTarget(previewSurface)
        mCaptureRequestBuilder.addTarget(recordSurface)

        mCameraDevice!!.createCaptureSession(
                Arrays.asList(recordSurface, previewSurface),
                object: CameraCaptureSession.StateCallback(){
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        mRecordCaptureSession = cameraCaptureSession
                        try {
                            mRecordCaptureSession?.setRepeatingRequest(mCaptureRequestBuilder.build(),null,null)
                        }catch (e:CameraAccessException){
                            e.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession?) {
                        Log.d(TAG,"onConfigureFailed in startRecord()")
                    }
                }, null)
    } catch (e: CameraAccessException) {
        Log.e(TAG, e.toString())
    } catch (e: IOException) {
        Log.e(TAG, e.toString())
    }

    private fun createVideoFolder() {
        val videoFile:File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        mVideoFolder = File(videoFile, "VibCam")
        if(!mVideoFolder.exists()){
            mVideoFolder.mkdirs()
        }
    }

    //TODO
    @SuppressLint("SimpleDateFormat")
    fun createVideoFileName(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val prepend = "VIDEO_$timestamp"
        val videoFile = File.createTempFile(prepend, ".mp4", mVideoFolder)
        mVideoFileName = videoFile.absolutePath
        return videoFile //зачем?
    }

    //close
    private fun stopPreview() {
        mPreviewCaptureSession?.stopRepeating()
        mPreviewCaptureSession?.close()
        mPreviewCaptureSession = null
    }
    private fun stopRecord(){
        //fps stop
        frameCounter.removeCallbacks(runnable)
        fpsCounter.text = "fps"

        mIsRecording = false
        recordButton.setBackgroundColor(android.graphics.Color.GREEN)
        mRecordCaptureSession?.stopRepeating()
        mPreviewCaptureSession?.close()
        mRecordCaptureSession = null
    }
    private fun closeCamera(){
        try{
            if(mIsRecording) stopRecord()
            stopPreview()
            mCameraDevice?.close()
            mCameraDevice = null
        }catch (e:InterruptedException) {
            throw RuntimeException("Can't close camera", e)
        }
    }

    //permissions
    private fun getPermission(){
        if(checkSelfPermission(this@MainActivity, CAMERA)
                != PackageManager.PERMISSION_GRANTED){
            if(shouldShowRequestPermissionRationale(this@MainActivity, CAMERA)){
                makeText(this@MainActivity,"I can't work without Camera!", LENGTH_SHORT).show()
            }
            ActivityCompat.requestPermissions(this@MainActivity, arrayOf(CAMERA),CAMERA_PERMISSION_REQUEST)
        }
        if(checkSelfPermission(this@MainActivity, WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED){
            if(shouldShowRequestPermissionRationale(this@MainActivity, WRITE_EXTERNAL_STORAGE)){
                makeText(this@MainActivity,"I can't work without saving!", LENGTH_SHORT).show()
            }
            ActivityCompat.requestPermissions(this@MainActivity, arrayOf(WRITE_EXTERNAL_STORAGE),STORAGE_PERMISSION_REQUEST)
        }
    }
    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<String>,
            grantResults: IntArray
    ) {
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST -> {
                if(grantResults.isNotEmpty() && grantResults[0] == PERMISSION_GRANTED){

                }else{
                    makeText(this@MainActivity,"I can't work without Camera!", LENGTH_SHORT).show()
                    this@MainActivity.finish()
                }
                return
            }
            STORAGE_PERMISSION_REQUEST -> {
                if(grantResults.isNotEmpty() && grantResults[0] == PERMISSION_GRANTED){

                }else{
                    makeText(this@MainActivity,"I can't work without saving!", LENGTH_SHORT).show()
                    this@MainActivity.finish()
                }
                return
            }
        }
    }
    //stabilisation
    fun stabilisation(image: Image?){
        if (null == image) return
        fps++
        image.close()
        return
    }


}
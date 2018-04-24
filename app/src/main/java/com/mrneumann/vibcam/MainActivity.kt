package com.mrneumann.vibcam

import android.Manifest.permission.*
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.support.v7.app.AppCompatActivity
import android.support.v4.content.ContextCompat.checkSelfPermission
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.app.ActivityCompat.shouldShowRequestPermissionRationale
import android.support.v4.content.PermissionChecker.PERMISSION_GRANTED
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Toast.*
import kotlinx.android.synthetic.main.activity_main.*
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
            closeCamera()
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

    //Activities
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        getPermission()
    }

    override fun onResume() {
        super.onResume()

        if(previewWindow.isAvailable){
            openCamera(previewWindow.width,previewWindow.height)
        }else{
            previewWindow.surfaceTextureListener = mSurfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        super.onPause()
    }

    private fun openCamera(width: Int, height: Int) {

        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for(camID in cameraManager.cameraIdList){
                val cameraCharacteristics = cameraManager.getCameraCharacteristics(camID) as CameraCharacteristics
                //определяем основая ли камера
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) continue
//                mVideoReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 10)
//                mVideoReader.setOnImageAvailableListener(mOnVideoAvailableListener, mBackgroundHandler)
                mCameraId = camID
                if(checkSelfPermission(this@MainActivity, CAMERA) == PERMISSION_GRANTED) {
                    cameraManager.openCamera(camID, mCameraDeviceStateCallback, null) //mBackgroundHandler
                }
                return
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
    private fun stopPreview() {
        mPreviewCaptureSession?.close()
        mPreviewCaptureSession = null
    }

    private fun closeCamera(){
        try{
            stopPreview()
            mCameraDevice?.close()
            mCameraDevice = null
        }catch (e:InterruptedException) {
            throw RuntimeException("Can't close camera", e)
        }
    }

    private fun getPermission(){
        if(checkSelfPermission(this@MainActivity, CAMERA)
                != PackageManager.PERMISSION_GRANTED){
            if(shouldShowRequestPermissionRationale(this@MainActivity, CAMERA)){
                makeText(this@MainActivity,"I can't work without Camera!", LENGTH_SHORT).show()
            }
            ActivityCompat.requestPermissions(this@MainActivity, arrayOf(CAMERA),CAMERA_PERMISSION_REQUEST)
        }
    }
    override fun onRequestPermissionsResult( //TODO check
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
        }
    }

}
/*
 * Copyright 2019 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tensorflow.lite.examples.posenet;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Bundle;
import android.os.Handler;;
import android.os.HandlerThread;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
//kotlin.math.abs?
import org.tensorflow.lite.examples.posenet.lib.BodyPart;
import org.tensorflow.lite.examples.posenet.lib.Device;
import org.tensorflow.lite.examples.posenet.lib.Person;
import org.tensorflow.lite.examples.posenet.lib.Posenet;

import static org.tensorflow.lite.examples.posenet.PosenetActivity.FRAGMENT_DIALOG;

public class PosenetActivity extends Fragment implements ActivityCompat.OnRequestPermissionsResultCallback {

/** List of body joints that should be connected.    */
ArrayList<Pair> bodyJoints = new ArrayList<Pair>(
        Arrays.asList(new Pair(BodyPart.LEFT_WRIST, BodyPart.LEFT_ELBOW),
                new Pair(BodyPart.LEFT_ELBOW, BodyPart.LEFT_SHOULDER),
                new Pair(BodyPart.LEFT_SHOULDER, BodyPart.RIGHT_SHOULDER),
                new Pair(BodyPart.RIGHT_SHOULDER, BodyPart.RIGHT_ELBOW),
                new Pair(BodyPart.RIGHT_ELBOW, BodyPart.RIGHT_WRIST),
                new Pair(BodyPart.LEFT_SHOULDER, BodyPart.LEFT_HIP),
                new Pair(BodyPart.LEFT_HIP, BodyPart.RIGHT_HIP),
                new Pair(BodyPart.RIGHT_HIP, BodyPart.RIGHT_SHOULDER),
                new Pair(BodyPart.LEFT_HIP, BodyPart.LEFT_KNEE),
                new Pair(BodyPart.LEFT_KNEE, BodyPart.LEFT_ANKLE),
                new Pair(BodyPart.RIGHT_HIP, BodyPart.RIGHT_KNEE),
                new Pair(BodyPart.RIGHT_KNEE, BodyPart.RIGHT_ANKLE)));

/** Threshold for confidence score. */
private double minConfidence = 0.5;

/** Radius of circle used to draw keypoints.  */
private float circleRadius = 8.0f;

/** Paint class holds the style and color information to draw geometries,text and bitmaps. */
//private var paint = Paint()

/** A shape for extracting frame data.   */
private int PREVIEW_WIDTH = 640;
private int PREVIEW_HEIGHT = 480;
public static final String ARG_MESSAGE = "message";
/**
 * Tag for the [Log].
 */
private String TAG = "PosenetActivity";

private String FRAGMENT_DIALOG = "dialog";

/** An object for the Posenet library.    */
private Posenet posenet;

/** ID of the current [CameraDevice].   */
private String cameraId = null; //nullable

/** A [SurfaceView] for camera preview.   */
private SurfaceView surfaceView = null; //nullable

/** A [CameraCaptureSession] for camera preview.   */
private CameraCaptureSession captureSession = null; //nullable

/** A reference to the opened [CameraDevice].    */
private CameraDevice cameraDevice = null; //nullable

/** The [android.util.Size] of camera preview.  */
private Size previewSize = null;

/** The [android.util.Size.getWidth] of camera preview. */
private int previewWidth = 0;

/** The [android.util.Size.getHeight] of camera preview.  */
private int previewHeight = 0;

/** A counter to keep count of total frames.  */
private int frameCounter = 0;

/** An IntArray to save image data in ARGB8888 format  */
private int[] rgbBytes;

/** A ByteArray to save image data in YUV format  */
private byte[] yuvBytes;  //???

/** An additional thread for running tasks that shouldn't block the UI.   */
private HandlerThread backgroundThread = null; //nullable

/** A [Handler] for running tasks in the background.    */
private Handler backgroundHandler = null; //nullable

/** An [ImageReader] that handles preview frame capture.   */
private ImageReader imageReader = null; //nullable

/** [CaptureRequest.Builder] for the camera preview   */
private CaptureRequest.Builder previewRequestBuilder = null; //nullable

/** [CaptureRequest] generated by [.previewRequestBuilder   */
private CaptureRequest previewRequest = null; //nullable

/** A [Semaphore] to prevent the app from exiting before closing the camera.    */
private Semaphore cameraOpenCloseLock = new Semaphore(1);

/** Whether the current camera device supports Flash or not.    */
private boolean flashSupported = false;

/** Orientation of the camera sensor.   */
private int sensorOrientation = 0;  //was null. Need Integer?

/** Abstract interface to someone holding a display surface.    */
private SurfaceHolder surfaceHolder = null; //nullable

/** [CameraDevice.StateCallback] is called when [CameraDevice] changes its state.   */
private class cameraStateCallback extends CameraDevice.StateCallback {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
                cameraOpenCloseLock.release();
                PosenetActivity.this.cameraDevice = cameraDevice;
                createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
                cameraOpenCloseLock.release();
                cameraDevice.close();
                PosenetActivity.this.cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int i) {
                onDisconnected(cameraDevice);

                //getActivity in a Fragment returns the activity the fragment is associated with
                if (PosenetActivity.this.getActivity() == null) {
                        return;
                }
                PosenetActivity.this.getActivity().finish();
        }
}

/**
 * A [CameraCaptureSession.CaptureCallback] that handles events related to JPEG capture.
 */
private class captureCallback extends CameraCaptureSession.CaptureCallback {
        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
                super.onCaptureProgressed(session, request, partialResult);

        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                super.onCaptureCompleted(session, request, result);

        }
}

/**
 * Shows a [Toast] on the UI thread.
 *
 * @param text The message to show
 */
private void showToast(final String text) {
        final Activity activity = PosenetActivity.this.getActivity();
        if (activity!=null)
                activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                                Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
                        }
                });
}


        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
                inflater.inflate(R.layout.tfe_pn_activity_posenet, container, false);
                return super.onCreateView(inflater, container, savedInstanceState);
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
                super.onViewCreated(view, savedInstanceState);
                surfaceView = view.findViewById(R.id.surfaceView);
                surfaceHolder = surfaceView.getHolder();
        }

        @Override
        public void onResume() {
                super.onResume();
                startBackgroundThread();
        }

        @Override
        public void onStart() {
                super.onStart();
                showToast("Added PoseNet submodule fragment into Activity");
                openCamera();
                posenet = new Posenet(this.getContext(), "posenet_model.tflite", Device.CPU);
        }

        @Override
        public void onPause() {
                closeCamera();
                stopBackgroundThread();
                super.onPause();
        }

        @Override
        public void onDestroy() {
                super.onDestroy();
                posenet.close();
        }


private void requestCameraPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                ConfirmationDialog confirmationDialog = new ConfirmationDialog();
                confirmationDialog.show(getChildFragmentManager(), FRAGMENT_DIALOG);
        }

        else {
                String[] camera = {Manifest.permission.CAMERA};
                requestPermissions(camera, Constants.REQUEST_CAMERA_PERMISSION);
        }
}


        @Override
        public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
                if (requestCode==Constants.REQUEST_CAMERA_PERMISSION) {
                        if (allPermissionsGranted(grantResults)) {
                                Error
                        }
                }
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }

        override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
        if (allPermissionsGranted(grantResults)) {
        ErrorDialog.newInstance(getString(R.string.tfe_pn_request_permission))
        .show(childFragmentManager, FRAGMENT_DIALOG)
        }
        } else {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
        }

//was a lambda expression in Kotlin
        /*
        private fun allPermissionsGranted(grantResults: IntArray) = grantResults.all {
    //this returns true if all elements of grantResults match this constant
    it == PackageManager.PERMISSION_GRANTED
  }
         */
private boolean allPermissionsGranted(int[] grantResults) {
        for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                        return false;
                }
        }
        return true;
}

/**
 * Sets up member variables related to camera.
 */
private fun setUpCameraOutputs() {
        val activity = activity
        val manager = activity!!.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
        for (cameraId in manager.cameraIdList) {
        val characteristics = manager.getCameraCharacteristics(cameraId)

        // We don't use a front facing camera in this sample.
        val cameraDirection = characteristics.get(CameraCharacteristics.LENS_FACING)
        if (cameraDirection != null &&
        cameraDirection == CameraCharacteristics.LENS_FACING_FRONT
        ) {
        continue
        }

        previewSize = Size(PREVIEW_WIDTH, PREVIEW_HEIGHT)

        imageReader = ImageReader.newInstance(
        PREVIEW_WIDTH, PREVIEW_HEIGHT,
        ImageFormat.YUV_420_888, /*maxImages*/ 2
        )

        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!

        previewHeight = previewSize!!.height
        previewWidth = previewSize!!.width

        // Initialize the storage bitmaps once when the resolution is known.
        rgbBytes = IntArray(previewWidth * previewHeight)

        // Check if the flash is supported.
        flashSupported =
        characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

        this.cameraId = cameraId

        // We've found a viable camera and finished setting up member variables,
        // so we don't need to iterate through other available cameras.
        return
        }
        } catch (e: CameraAccessException) {
        Log.e(TAG, e.toString())
        } catch (e: NullPointerException) {
        // Currently an NPE is thrown when the Camera2API is used but not supported on the
        // device this code runs.
        ErrorDialog.newInstance(getString(R.string.tfe_pn_camera_error))
        .show(childFragmentManager, FRAGMENT_DIALOG)
        }
        }

/**
 * Opens the camera specified by [PosenetActivity.cameraId].
 */
private fun openCamera() {
        val permissionCamera = getContext()!!.checkPermission(
        Manifest.permission.CAMERA, Process.myPid(), Process.myUid()
        )
        if (permissionCamera != PackageManager.PERMISSION_GRANTED) {
        requestCameraPermission()
        }
        setUpCameraOutputs()
        val manager = activity!!.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
        // Wait for camera to open - 2.5 seconds is sufficient
        if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
        throw RuntimeException("Time out waiting to lock camera opening.")
        }
        manager.openCamera(cameraId!!, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
        Log.e(TAG, e.toString())
        } catch (e: InterruptedException) {
        throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
        }

/**
 * Closes the current [CameraDevice].
 */
private fun closeCamera() {
        if (captureSession == null) {
        return
        }

        try {
        cameraOpenCloseLock.acquire()
        captureSession!!.close()
        captureSession = null
        cameraDevice!!.close()
        cameraDevice = null
        imageReader!!.close()
        imageReader = null
        } catch (e: InterruptedException) {
        throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
        cameraOpenCloseLock.release()
        }
        }

/**
 * Starts a background thread and its [Handler].
 */
private fun startBackgroundThread() {
        backgroundThread = HandlerThread("imageAvailableListener").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        }

/**
 * Stops the background thread and its [Handler].
 */
private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
        backgroundThread?.join()
        backgroundThread = null
        backgroundHandler = null
        } catch (e: InterruptedException) {
        Log.e(TAG, e.toString())
        }
        }

/** Fill the yuvBytes with data from image planes.   */
private fun fillBytes(planes: Array<Image.Plane>, yuvBytes: Array<ByteArray?>) {
        // Row stride is the total number of bytes occupied in memory by a row of an image.
        // Because of the variable row stride it's not possible to know in
        // advance the actual necessary dimensions of the yuv planes.
        for (i in planes.indices) {
        val buffer = planes[i].buffer
        if (yuvBytes[i] == null) {
        yuvBytes[i] = ByteArray(buffer.capacity())
        }
        buffer.get(yuvBytes[i]!!)
        }
        }

/** A [OnImageAvailableListener] to receive frames as they are available.  */
private var imageAvailableListener = object : OnImageAvailableListener {
        override fun onImageAvailable(imageReader: ImageReader) {
        // We need wait until we have some size from onPreviewSizeChosen
        if (previewWidth == 0 || previewHeight == 0) {
        return
        }

        val image = imageReader.acquireLatestImage() ?: return
        fillBytes(image.planes, yuvBytes)

        ImageUtils.convertYUV420ToARGB8888(
        yuvBytes[0]!!,
        yuvBytes[1]!!,
        yuvBytes[2]!!,
        previewWidth,
        previewHeight,
        /*yRowStride=*/ image.planes[0].rowStride,
        /*uvRowStride=*/ image.planes[1].rowStride,
        /*uvPixelStride=*/ image.planes[1].pixelStride,
        rgbBytes
        )

        // Create bitmap from int array
        val imageBitmap = Bitmap.createBitmap(
        rgbBytes, previewWidth, previewHeight,
        Bitmap.Config.ARGB_8888
        )

        // Create rotated version for portrait display
        val rotateMatrix = Matrix()
        rotateMatrix.postRotate(90.0f)

        val rotatedBitmap = Bitmap.createBitmap(
        imageBitmap, 0, 0, previewWidth, previewHeight,
        rotateMatrix, true
        )
        image.close()

        processImage(rotatedBitmap)
        }
        }

/** Crop Bitmap to maintain aspect ratio of model input.   */
private fun cropBitmap(bitmap: Bitmap): Bitmap {
        val bitmapRatio = bitmap.height.toFloat() / bitmap.width
        val modelInputRatio = MODEL_HEIGHT.toFloat() / MODEL_WIDTH
        var croppedBitmap = bitmap

        // Acceptable difference between the modelInputRatio and bitmapRatio to skip cropping.
        val maxDifference = 1e-5

        // Checks if the bitmap has similar aspect ratio as the required model input.
        when {
        abs(modelInputRatio - bitmapRatio) < maxDifference -> return croppedBitmap
        modelInputRatio < bitmapRatio -> {
        // New image is taller so we are height constrained.
        val cropHeight = bitmap.height - (bitmap.width.toFloat() / modelInputRatio)
        croppedBitmap = Bitmap.createBitmap(
        bitmap,
        0,
        (cropHeight / 2).toInt(),
        bitmap.width,
        (bitmap.height - cropHeight).toInt()
        )
        }
        else -> {
        val cropWidth = bitmap.width - (bitmap.height.toFloat() * modelInputRatio)
        croppedBitmap = Bitmap.createBitmap(
        bitmap,
        (cropWidth / 2).toInt(),
        0,
        (bitmap.width - cropWidth).toInt(),
        bitmap.height
        )
        }
        }
        return croppedBitmap
        }

/** Set the paint color and size.    */
private fun setPaint() {
        paint.color = Color.RED
        paint.textSize = 80.0f
        paint.strokeWidth = 8.0f
        }

/** Draw bitmap on Canvas.   */
private fun draw(canvas: Canvas, person: Person, bitmap: Bitmap) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        // Draw `bitmap` and `person` in square canvas.
        val screenWidth: Int
        val screenHeight: Int
        val left: Int
        val right: Int
        val top: Int
        val bottom: Int


        if (canvas.height > canvas.width) {
        screenWidth = canvas.width
        screenHeight = canvas.width
        left = 0
        top = (canvas.height - canvas.width) / 2
        }


        else {
        screenWidth = canvas.height
        screenHeight = canvas.height
        left = (canvas.width - canvas.height) / 2
        top = 0
        }
        right = left + screenWidth
        bottom = top + screenHeight

        setPaint()
        canvas.drawBitmap(
        bitmap,
        Rect(0, 0, bitmap.width, bitmap.height),
        Rect(left, top, right, bottom),
        paint
        )

        val widthRatio = screenWidth.toFloat() / MODEL_WIDTH
        val heightRatio = screenHeight.toFloat() / MODEL_HEIGHT

        // Draw key points over the image.
        for (keyPoint in person.keyPoints) {
        if (keyPoint.score > minConfidence) {
        val position = keyPoint.position
        val adjustedX: Float = position.x.toFloat() * widthRatio + left
        val adjustedY: Float = position.y.toFloat() * heightRatio + top
        canvas.drawCircle(adjustedX, adjustedY, circleRadius, paint)
        }
        }

        for (line in bodyJoints) {
        if ((person.keyPoints[line.first.ordinal].score > minConfidence) and (person.keyPoints[line.second.ordinal].score > minConfidence))
        {
        canvas.drawLine(
        person.keyPoints[line.first.ordinal].position.x.toFloat() * widthRatio + left,
        person.keyPoints[line.first.ordinal].position.y.toFloat() * heightRatio + top,
        person.keyPoints[line.second.ordinal].position.x.toFloat() * widthRatio + left,
        person.keyPoints[line.second.ordinal].position.y.toFloat() * heightRatio + top,
        paint
        )
        }

        }

        canvas.drawText(
        "Score: %.2f".format(person.score),
        (15.0f * widthRatio),
        (30.0f * heightRatio + bottom),
        paint
        )
        canvas.drawText(
        "Device: %s".format(posenet.device),
        (15.0f * widthRatio),
        (50.0f * heightRatio + bottom),
        paint
        )
        canvas.drawText(
        "Time: %.2f ms".format(posenet.lastInferenceTimeNanos * 1.0f / 1_000_000),
        (15.0f * widthRatio),
        (70.0f * heightRatio + bottom),
        paint
        )

        // Draw!
        surfaceHolder!!.unlockCanvasAndPost(canvas)
        }

/** Process image using Posenet library.   */
private fun processImage(bitmap: Bitmap) {
        // Crop bitmap.
        val croppedBitmap = cropBitmap(bitmap)

        // Created scaled version of bitmap for model input.
        val scaledBitmap = Bitmap.createScaledBitmap(croppedBitmap, MODEL_WIDTH, MODEL_HEIGHT, true)

        // Perform inference.
        val person = posenet.estimateSinglePose(scaledBitmap)
        val canvas: Canvas = surfaceHolder!!.lockCanvas()
        draw(canvas, person, scaledBitmap)
        }

/**
 * Creates a new [CameraCaptureSession] for camera preview.
 */
private fun createCameraPreviewSession() {
        try {
        // We capture images from preview in YUV format.
        imageReader = ImageReader.newInstance(
        previewSize!!.width, previewSize!!.height, ImageFormat.YUV_420_888, 2
        )
        imageReader!!.setOnImageAvailableListener(imageAvailableListener, backgroundHandler)

        // This is the surface we need to record images for processing.
        val recordingSurface = imageReader!!.surface

        // We set up a CaptureRequest.Builder with the output Surface.
        previewRequestBuilder = cameraDevice!!.createCaptureRequest(
        CameraDevice.TEMPLATE_PREVIEW
        )
        previewRequestBuilder!!.addTarget(recordingSurface)

        // Here, we create a CameraCaptureSession for camera preview.
        cameraDevice!!.createCaptureSession(
        listOf(recordingSurface),
        object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
        // The camera is already closed
        if (cameraDevice == null) return

        // When the session is ready, we start displaying the preview.
        captureSession = cameraCaptureSession
        try {
        // Auto focus should be continuous for camera preview.
        previewRequestBuilder!!.set(
        CaptureRequest.CONTROL_AF_MODE,
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )
        // Flash is automatically enabled when necessary.
        setAutoFlash(previewRequestBuilder!!)

        // Finally, we start displaying the camera preview.
        previewRequest = previewRequestBuilder!!.build()
        captureSession!!.setRepeatingRequest(
        previewRequest!!,
        captureCallback, backgroundHandler
        )
        } catch (e: CameraAccessException) {
        Log.e(TAG, e.toString())
        }
        }

        override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
        showToast("Failed")
        }
        },
        null
        )
        } catch (e: CameraAccessException) {
        Log.e(TAG, e.toString())
        }
        }

private fun setAutoFlash(requestBuilder: CaptureRequest.Builder) {
        if (flashSupported) {
        requestBuilder.set(
        CaptureRequest.CONTROL_AE_MODE,
        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
        )
        }
        }

/**
 * Shows an error message dialog.
 */
private static class ErrorDialog extends DialogFragment {
        @NonNull
        @Override
        public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
                assert getArguments() != null;
                new AlertDialog.Builder(getActivity()).setMessage(getArguments().getString(ARG_MESSAGE))
                        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialogInterface, int i) {
                                        Objects.requireNonNull(getActivity()).finish();
                                }
                        }).create();
                return super.onCreateDialog(savedInstanceState);
        }


        public static ErrorDialog newInstance(String message) {

                Bundle args = new Bundle();

                args.putString(ARG_MESSAGE, message);

                ErrorDialog fragment = new ErrorDialog();
                fragment.setArguments(args);
                return fragment;
        }
}

              companion object {
                        /**
                         * Conversion from screen rotation to JPEG orientation.
                         */
                        private val ORIENTATIONS = SparseIntArray()


                                init {
                                ORIENTATIONS.append(Surface.ROTATION_0, 90)
                                ORIENTATIONS.append(Surface.ROTATION_90, 0)
                                ORIENTATIONS.append(Surface.ROTATION_180, 270)
                                ORIENTATIONS.append(Surface.ROTATION_270, 180)
                                }
        }
}

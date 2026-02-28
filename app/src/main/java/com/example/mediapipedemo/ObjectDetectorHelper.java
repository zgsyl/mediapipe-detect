package com.example.mediapipedemo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.SystemClock;
import android.util.Log;

import androidx.camera.core.ImageProxy;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult;

public class ObjectDetectorHelper {
    private static final String TAG = "ObjectDetectorHelper";
    // We download the int8 quantized efficientdet lite model which is very fast on
    // mobile
    private static final String MODEL_PATH = "efficientdet_lite0.tflite";

    private final Context context;
    private final DetectorListener listener;
    private ObjectDetector objectDetector;

    public interface DetectorListener {
        void onError(String error);

        void onResults(ObjectDetectorResult results, long inferenceTime, int imageHeight, int imageWidth);
    }

    public ObjectDetectorHelper(Context context, DetectorListener listener) {
        this.context = context;
        this.listener = listener;
        setupObjectDetector();
    }

    private void setupObjectDetector() {
        try {
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_PATH)
                    .build();

            ObjectDetector.ObjectDetectorOptions options = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setMaxResults(5) // Don't need to find too many things
                    .setScoreThreshold(0.6f) // Only care about confident detections
                    .setResultListener(this::returnLivestreamResult)
                    .setErrorListener(this::returnLivestreamError)
                    .build();

            objectDetector = ObjectDetector.createFromOptions(context, options);
        } catch (Exception e) {
            Log.e(TAG, "ObjectDetector failed to initialize. Error: " + e.getMessage());
            if (listener != null) {
                listener.onError("ObjectDetector failed to initialize.");
            }
        }
    }

    public void detectLiveStream(ImageProxy imageProxy, boolean isFrontCamera) {
        if (objectDetector == null)
            return;

        long frameTime = SystemClock.uptimeMillis();

        Bitmap bitmapBuffer = imageProxy.toBitmap();

        Matrix matrix = new Matrix();
        matrix.postRotate(imageProxy.getImageInfo().getRotationDegrees());
        if (isFrontCamera) {
            // Important: The object detector needs to see the image just like the Pose
            // Landmarker
            matrix.postScale(-1f, 1f, bitmapBuffer.getWidth() / 2f, bitmapBuffer.getHeight() / 2f);
        }

        Bitmap rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.getWidth(), bitmapBuffer.getHeight(), matrix, false);

        MPImage mpImage = new BitmapImageBuilder(rotatedBitmap).build();

        objectDetector.detectAsync(mpImage, frameTime);

        // Required to close imageProxy to free memory for CameraX to produce next frame
        imageProxy.close();
    }

    private void returnLivestreamResult(ObjectDetectorResult result, MPImage inputImage) {
        long finishTimeMs = SystemClock.uptimeMillis();
        long inferenceTime = finishTimeMs - result.timestampMs();

        if (listener != null) {
            listener.onResults(result, inferenceTime, inputImage.getHeight(), inputImage.getWidth());
        }
    }

    private void returnLivestreamError(RuntimeException error) {
        if (listener != null) {
            listener.onError(error.getMessage() != null ? error.getMessage() : "Unknown error");
        }
    }

    public void close() {
        if (objectDetector != null) {
            objectDetector.close();
        }
    }
}

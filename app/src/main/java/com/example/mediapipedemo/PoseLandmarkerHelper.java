package com.example.mediapipedemo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.VisibleForTesting;
import androidx.camera.core.ImageProxy;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

public class PoseLandmarkerHelper {
    private static final String TAG = "PoseLandmarkerHelper";
    private static final String MODEL_PATH = "pose_landmarker.task";

    private final Context context;
    private final PoseLandmarkerListener listener;
    private PoseLandmarker poseLandmarker;

    public interface PoseLandmarkerListener {
        void onError(String error);

        void onResults(PoseLandmarkerResult result, long inferenceTime);
    }

    public PoseLandmarkerHelper(Context context, PoseLandmarkerListener listener) {
        this.context = context;
        this.listener = listener;
        setupPoseLandmarker();
    }

    private void setupPoseLandmarker() {
        try {
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_PATH)
                    .build();

            PoseLandmarker.PoseLandmarkerOptions options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setResultListener(this::returnLivestreamResult)
                    .setErrorListener(this::returnLivestreamError)
                    .build();

            poseLandmarker = PoseLandmarker.createFromOptions(context, options);
        } catch (Exception e) {
            Log.e(TAG, "PoseLandmarker failed to initialize. Error: " + e.getMessage());
            if (listener != null) {
                listener.onError("PoseLandmarker failed to initialize.");
            }
        }
    }

    public void detectLiveStream(ImageProxy imageProxy, boolean isFrontCamera) {
        if (poseLandmarker == null)
            return;

        long frameTime = SystemClock.uptimeMillis();

        Bitmap bitmapBuffer = imageProxy.toBitmap();

        Matrix matrix = new Matrix();
        matrix.postRotate(imageProxy.getImageInfo().getRotationDegrees());
        if (isFrontCamera) {
            matrix.postScale(-1f, 1f, bitmapBuffer.getWidth() / 2f, bitmapBuffer.getHeight() / 2f);
        }

        Bitmap rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.getWidth(), bitmapBuffer.getHeight(), matrix, false);

        MPImage mpImage = new BitmapImageBuilder(rotatedBitmap).build();

        poseLandmarker.detectAsync(mpImage, frameTime);

        // Don't forget to close the ImageProxy when using MediaPipe detectAsync which
        // doesn't automatically close it in some version wrappers or handled upstream.
        // Actually, we should close it after image copied.
        imageProxy.close();
    }

    private void returnLivestreamResult(PoseLandmarkerResult result, MPImage inputImage) {
        long finishTimeMs = SystemClock.uptimeMillis();
        long inferenceTime = finishTimeMs - result.timestampMs();

        if (listener != null) {
            listener.onResults(result, inferenceTime);
        }
    }

    private void returnLivestreamError(RuntimeException error) {
        if (listener != null) {
            listener.onError(error.getMessage() != null ? error.getMessage() : "An unknown error has occurred");
        }
    }

    public void close() {
        if (poseLandmarker != null) {
            poseLandmarker.close();
        }
    }
}

package com.example.mediapipedemo;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.camera.core.ImageAnalysis;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.Detection;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MediaPipeDemo";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = new String[] { Manifest.permission.CAMERA };

    private PreviewView viewFinder;
    private OverlayView overlayView;
    private ExecutorService cameraExecutor;
    private PoseLandmarkerHelper poseLandmarkerHelper;
    private ObjectDetectorHelper objectDetectorHelper;
    private android.widget.TextView actionStatusText;

    // Multi-modal state
    private PoseLandmarkerResult lastPoseResult;
    private ObjectDetectorResult lastObjectResult;

    // Frame Alternation Counter
    private int frameCounter = 0;

    // Smoothing list to prevent rapid flickering of status
    private final java.util.LinkedList<String> recentStatuses = new java.util.LinkedList<>();
    private static final int SMOOTHING_WINDOW_SIZE = 10;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewFinder = findViewById(R.id.viewFinder);
        overlayView = findViewById(R.id.overlayView);
        actionStatusText = findViewById(R.id.actionStatusText);

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                    this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        cameraExecutor = Executors.newSingleThreadExecutor();

        poseLandmarkerHelper = new PoseLandmarkerHelper(this, new PoseLandmarkerHelper.PoseLandmarkerListener() {
            @Override
            public void onError(String error) {
                Log.e(TAG, "MediaPipe Pose Error: " + error);
            }

            @Override
            public void onResults(PoseLandmarkerResult result, long inferenceTime) {
                lastPoseResult = result;
                processMultiModalData();
            }
        });

        objectDetectorHelper = new ObjectDetectorHelper(this, new ObjectDetectorHelper.DetectorListener() {
            @Override
            public void onError(String error) {
                Log.e(TAG, "MediaPipe Object Error: " + error);
            }

            @Override
            public void onResults(ObjectDetectorResult results, long inferenceTime) {
                lastObjectResult = results;
                processMultiModalData();
            }
        });
    }

    private void processMultiModalData() {
        if (lastPoseResult == null || lastPoseResult.landmarks().isEmpty()) {
            overlayView.post(() -> overlayView.setResults(null, 1, 1));
            updateStatusText("未检测到人体");
            return;
        }

        java.util.List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> landmarks = lastPoseResult
                .landmarks().get(0);

        // Pass results to OverlayView for drawing
        overlayView.post(() -> {
            overlayView.setResults(lastPoseResult, viewFinder.getHeight(), viewFinder.getWidth());
        });

        String currentStatus = analyzeAction(landmarks, lastObjectResult);
        updateStatusText(currentStatus);
    }

    private String analyzeAction(
            java.util.List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> poseLandmarks,
            ObjectDetectorResult objectResult) {

        if (poseLandmarks.size() < 17)
            return "分析中...";

        com.google.mediapipe.tasks.components.containers.NormalizedLandmark leftShoulder = poseLandmarks.get(11);
        com.google.mediapipe.tasks.components.containers.NormalizedLandmark rightShoulder = poseLandmarks.get(12);
        com.google.mediapipe.tasks.components.containers.NormalizedLandmark leftWrist = poseLandmarks.get(15);
        com.google.mediapipe.tasks.components.containers.NormalizedLandmark rightWrist = poseLandmarks.get(16);

        float shoulderAvgY = (leftShoulder.y() + rightShoulder.y()) / 2f;
        float wristAvgY = (leftWrist.y() + rightWrist.y()) / 2f;

        float handsToShoulderDiff = wristAvgY - shoulderAvgY;
        float handsDistanceX = Math.abs(leftWrist.x() - rightWrist.x());

        boolean isHandsUp = handsToShoulderDiff < 0.25f;
        boolean isHandsClose = handsDistanceX < 0.4f;

        // Fused Logic: Is there a cell phone in the frame?
        boolean seePhone = false;
        if (objectResult != null) {
            for (Detection detection : objectResult.detections()) {
                for (Category category : detection.categories()) {
                    if (category.categoryName().equals("cell phone") && category.score() > 0.4f) {
                        seePhone = true;
                    }
                }
            }
        }

        if (isHandsUp && seePhone) {
            return "📱 抓到啦！正在玩手机！";
        }

        if (isHandsUp && isHandsClose) {
            return "📱 疑似在玩手机/手持物";
        } else if (handsToShoulderDiff > 0.45f) {
            return "✍️ 努力写字中...";
        }

        return "🤔 正常姿态 / 未知动作";
    }

    private synchronized void updateStatusText(String newStatus) {
        recentStatuses.add(newStatus);
        if (recentStatuses.size() > SMOOTHING_WINDOW_SIZE) {
            recentStatuses.poll();
        }

        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (String status : recentStatuses) {
            counts.put(status, counts.getOrDefault(status, 0) + 1);
        }

        String mostFrequentStatus = newStatus;
        int maxCount = 0;
        for (java.util.Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                mostFrequentStatus = entry.getKey();
            }
        }

        final String finalStatus = mostFrequentStatus;
        runOnUiThread(() -> {
            if (actionStatusText != null && !actionStatusText.getText().toString().equals(finalStatus)) {
                actionStatusText.setText(finalStatus);

                if (finalStatus.contains("写字")) {
                    actionStatusText.setBackgroundColor(android.graphics.Color.parseColor("#884CAF50")); // Green
                } else if (finalStatus.contains("玩手机")) {
                    actionStatusText.setBackgroundColor(android.graphics.Color.parseColor("#88F44336")); // Red
                } else if (finalStatus.contains("未检测到人体") || finalStatus.contains("分析中")) {
                    actionStatusText.setBackgroundColor(android.graphics.Color.parseColor("#88000000")); // Black
                } else {
                    actionStatusText.setBackgroundColor(android.graphics.Color.parseColor("#88FF9800")); // Orange
                }
            }
        });
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                // Select front camera as a default for action recognition
                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
                boolean isFrontCamera = cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA;

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, image -> {
                    frameCounter++;
                    if (frameCounter % 2 == 0) {
                        if (poseLandmarkerHelper != null) {
                            poseLandmarkerHelper.detectLiveStream(image, isFrontCamera);
                        } else {
                            image.close();
                        }
                    } else {
                        if (objectDetectorHelper != null) {
                            objectDetectorHelper.detectLiveStream(image, isFrontCamera);
                        } else {
                            image.close();
                        }
                    }
                });

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Use case binding failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (poseLandmarkerHelper != null) {
            poseLandmarkerHelper.close();
        }
        if (objectDetectorHelper != null) {
            objectDetectorHelper.close();
        }
        cameraExecutor.shutdown();
    }
}

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
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MediaPipeDemo";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = new String[] { Manifest.permission.CAMERA };

    private PreviewView viewFinder;
    private OverlayView overlayView;
    private ExecutorService cameraExecutor;
    private PoseLandmarkerHelper poseLandmarkerHelper;
    private android.widget.TextView actionStatusText;

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
                Log.e(TAG, "MediaPipe Error: " + error);
            }

            @Override
            public void onResults(PoseLandmarkerResult result, long inferenceTime) {
                if (result.landmarks().isEmpty()) {
                    overlayView.post(() -> overlayView.setResults(null, 1, 1));
                    updateStatusText("未检测到人体");
                    return;
                }

                // Get the coordinates
                java.util.List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> landmarks = result
                        .landmarks().get(0);

                String currentStatus = analyzeAction(landmarks);

                // Pass results to OverlayView for drawing
                overlayView.post(() -> {
                    overlayView.setResults(result, viewFinder.getHeight(), viewFinder.getWidth());
                });

                updateStatusText(currentStatus);
            }
        });
    }

    private String analyzeAction(
            java.util.List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> landmarks) {
        if (landmarks.size() < 17)
            return "分析中...";

        com.google.mediapipe.tasks.components.containers.NormalizedLandmark leftShoulder = landmarks.get(11);
        com.google.mediapipe.tasks.components.containers.NormalizedLandmark rightShoulder = landmarks.get(12);
        com.google.mediapipe.tasks.components.containers.NormalizedLandmark leftWrist = landmarks.get(15);
        com.google.mediapipe.tasks.components.containers.NormalizedLandmark rightWrist = landmarks.get(16);

        float shoulderAvgY = (leftShoulder.y() + rightShoulder.y()) / 2f;
        float wristAvgY = (leftWrist.y() + rightWrist.y()) / 2f;

        // Y coordinates: 0 is top, 1 is bottom. Smaller diff means hands are higher
        // relative to shoulders.
        float handsToShoulderDiff = wristAvgY - shoulderAvgY;

        // X coordinates: 0 is left, 1 is right.
        float handsDistanceX = Math.abs(leftWrist.x() - rightWrist.x());

        // Rules for classification
        if (handsToShoulderDiff < 0.25f && handsDistanceX < 0.4f) {
            return "📱 正在玩手机";
        } else if (handsToShoulderDiff > 0.45f) {
            return "✍️ 努力写字中...";
        }

        return "🤔 正常姿态 / 未知动作";
    }

    private void updateStatusText(String newStatus) {
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
                    if (poseLandmarkerHelper != null) {
                        poseLandmarkerHelper.detectLiveStream(image, isFrontCamera);
                    } else {
                        image.close();
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
        cameraExecutor.shutdown();
    }
}

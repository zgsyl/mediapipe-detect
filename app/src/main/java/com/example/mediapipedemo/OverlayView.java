package com.example.mediapipedemo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

import java.util.List;

public class OverlayView extends View {
    private PoseLandmarkerResult results;
    private Paint pointPaint;
    private Paint linePaint;

    private float scaleFactor = 1f;
    private int imageWidth = 1;
    private int imageHeight = 1;

    // Pairs of landmarks to draw lines showing the skeleton structure
    private static final int[][] POSE_LANDMARKS_CONNECTIONS = {
            { 11, 12 }, { 11, 13 }, { 13, 15 }, { 12, 14 }, { 14, 16 }, { 11, 23 }, { 12, 24 }, { 23, 24 }, { 23, 25 },
            { 24, 26 }, { 25, 27 }, { 26, 28 }, { 27, 29 }, { 28, 30 }, { 29, 31 }, { 30, 32 }, { 27, 31 }, { 28, 32 },
            { 15, 21 }, { 16, 22 }, { 15, 17 }, { 16, 18 }, { 15, 19 }, { 16, 20 }, { 17, 19 }, { 18, 20 }
    };

    public OverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initPaints();
    }

    private void initPaints() {
        linePaint = new Paint();
        linePaint.setColor(Color.GREEN);
        linePaint.setStrokeWidth(8f);
        linePaint.setStyle(Paint.Style.STROKE);

        pointPaint = new Paint();
        pointPaint.setColor(Color.RED);
        pointPaint.setStrokeWidth(12f);
        pointPaint.setStyle(Paint.Style.FILL);
    }

    public void setResults(PoseLandmarkerResult poseLandmarkerResults, int imageHeight, int imageWidth) {
        this.results = poseLandmarkerResults;
        this.imageHeight = imageHeight;
        this.imageWidth = imageWidth;
        invalidate(); // Trigger a redraw
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (results == null || results.landmarks().isEmpty()) {
            return;
        }

        // Calculate scaling assuming preview behavior is ScaleType.FILL_CENTER
        scaleFactor = Math.max(getWidth() * 1f / imageWidth, getHeight() * 1f / imageHeight);

        // Find center difference because ScaleType.FILL_CENTER zooms from center
        float startX = (getWidth() - imageWidth * scaleFactor) / 2f;
        float startY = (getHeight() - imageHeight * scaleFactor) / 2f;

        List<NormalizedLandmark> landmarks = results.landmarks().get(0);

        // Draw connections (bones)
        for (int[] connection : POSE_LANDMARKS_CONNECTIONS) {
            NormalizedLandmark startLandmark = landmarks.get(connection[0]);
            NormalizedLandmark endLandmark = landmarks.get(connection[1]);

            canvas.drawLine(
                    startLandmark.x() * imageWidth * scaleFactor + startX,
                    startLandmark.y() * imageHeight * scaleFactor + startY,
                    endLandmark.x() * imageWidth * scaleFactor + startX,
                    endLandmark.y() * imageHeight * scaleFactor + startY,
                    linePaint);
        }

        // Draw points (joints)
        for (NormalizedLandmark landmark : landmarks) {
            canvas.drawCircle(
                    landmark.x() * imageWidth * scaleFactor + startX,
                    landmark.y() * imageHeight * scaleFactor + startY,
                    8f,
                    pointPaint);
        }
    }
}

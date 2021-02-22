package com.jtb.hls;

import android.os.Bundle;
import android.os.Handler;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class AppLogs extends AppCompatActivity {

    TextView logsTextView;
    ScrollView scrollView;
    LinearLayout optionsView;
    CheckBox autoScroll;
    boolean visibilityFlag = false, autoScrollFlag = true;

    String TAG = "HLS_AppLogs";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_logs);

        logsTextView = findViewById(R.id.app_logs_text);
        scrollView = findViewById(R.id.app_logs_scroller);
        optionsView = findViewById(R.id.app_logs_options);
        autoScroll = findViewById(R.id.app_logs_auto_scroll);
        optionsView.setVisibility(View.GONE);

        final GestureDetector gDetector = new GestureDetector(getBaseContext(), new GestureDetector.SimpleOnGestureListener() {

            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (!visibilityFlag) {
                    optionsView.setVisibility(View.VISIBLE);
                    TranslateAnimation animate = new TranslateAnimation(0, 0, -optionsView.getHeight(), 0);
                    animate.setDuration(500);
                    animate.setFillAfter(true);
                    optionsView.startAnimation(animate);
                    visibilityFlag = true;

                } else {
                    TranslateAnimation animate = new TranslateAnimation(0, 0, 0, -optionsView.getHeight());
                    animate.setDuration(500);
                    animate.setFillAfter(true);
                    animate.setAnimationListener(new Animation.AnimationListener() {
                        @Override
                        public void onAnimationStart(Animation animation) {

                        }

                        @Override
                        public void onAnimationEnd(Animation animation) {
                            optionsView.clearAnimation();
                            optionsView.setVisibility(View.GONE);

                        }

                        @Override
                        public void onAnimationRepeat(Animation animation) {

                        }
                    });
                    optionsView.startAnimation(animate);
                    visibilityFlag = false;
                }

                return true;
            }
        });
        scrollView.setOnTouchListener((v, event) -> gDetector.onTouchEvent(event));

        autoScroll.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                autoScrollFlag = isChecked;
            }
        });

        Handler h = new Handler();
        Runnable r = new Runnable() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Process process = Runtime.getRuntime().exec("logcat -d HLS_AppLogs:V HLS_Service:V HLS_WebView:V HLS_Request:V *:S");
                            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));

                            StringBuilder appLogs = new StringBuilder();
                            String line;
                            while ((line = bufferedReader.readLine()) != null) {
                                appLogs.append(line + "\n\n");
                            }
                            logsTextView.setText(appLogs.toString());
                            if (autoScrollFlag) {
                                scrollView.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        scrollView.fullScroll(View.FOCUS_DOWN);
                                    }
                                });
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                });
                h.postDelayed(this, 1000);
            }
        };
        h.postDelayed(r, 1000);
    }
}

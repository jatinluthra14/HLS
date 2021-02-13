package com.example.hls;

import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class AppLogs extends AppCompatActivity {

    TextView logsTextView;
    ScrollView scrollView;

    String TAG = "AppLogs";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_logs);

        logsTextView = findViewById(R.id.app_logs_text);
        scrollView = findViewById(R.id.app_logs_scroller);
        Handler h = new Handler();
        Runnable r = new Runnable() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Process process = Runtime.getRuntime().exec("logcat -d HLSService:V WebView:V Request_WebView:V AppLogs:V *:S");
                            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));

                            StringBuilder appLogs = new StringBuilder();
                            String line;
                            while ((line = bufferedReader.readLine()) != null) {
                                appLogs.append(line + "\n\n");
                            }
                            logsTextView.setText(appLogs.toString());
                            scrollView.post(new Runnable() {
                                @Override
                                public void run() {
                                    scrollView.fullScroll(View.FOCUS_DOWN);
                                }
                            });
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

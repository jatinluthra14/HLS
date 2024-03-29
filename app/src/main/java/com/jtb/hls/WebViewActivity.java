package com.jtb.hls;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.app.ActivityCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.mapper.VideoFormat;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class WebViewActivity extends AppCompatActivity implements View.OnClickListener {

    SharedPreferences sharedPref;
    SharedPreferences.Editor sharedPrefEditor;
    WebView webView;
    EditText editText;
    ProgressBar progressBar;
    ImageButton back, forward, stop, refresh, homeButton, menuButton, streamButton, downloadButton, optionsButton, goButton;
    Map<String, Map<String, Object>> hlsStack;
    Map<String, String> streamStack;
    Map<String, ArrayList<String>> m3u8_ts_map;
    Queue<String> allRequests;
    ConcurrentLinkedDeque<String> mediaRequests;
    HashSet<String> allowedRedirectDomains, blockedRedirectDomains, blockedRedirectDomainsForever;
    Set<String> markedAdDomains;
    String selected_path = "", current_url = "", view_mode = "mobile", TAG="HLS_WebView", RequestTAG="HLS_Request";
    TextView bubble_text;
    int stream_flag = 0, min1_flag = 0;
    boolean ad_parsing_flag, ad_blocking_flag;
    long current_page_time = 0;

    Handler h1,h2;
    Runnable r1,r2;
    Executor executor;

    public static String[] PERMISSIONS_ALL = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    static OkHttpClient client = new OkHttpClient();

    void get_request(String url, String orig_url){
        if (!(url.equals(orig_url)))
            Log.d(TAG, "Called Get_Request with Url: " + url + " Orig_URL: " + orig_url);
        else {
            if (streamStack.containsKey(Util.clearQuery(url))) {
                Log.d(TAG, "KEY FOUND STREAM STACK!");
                return;
            }
        }
        if (!hlsStack.containsKey(orig_url.trim())) {
            Uri uri = Uri.parse(orig_url.trim());
            String spath = Util.clearQuery(orig_url);
            hlsStack.put(uri.toString(), new LinkedHashMap<String, Object>(){{put("duration", ""); put("uri", uri); put("strip_url", spath);}});
            if (spath.endsWith(".m3u8")) stream_flag = 1;
        }
        Request request = new Request.Builder()
                .url(url)
                .build();

        Log.d(TAG, "Sending Media Request");
        client.newCall(request)
                .enqueue(new Callback() {
                    @Override
                    public void onFailure(final Call call, IOException e) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {}
                        });
                    }

                    @Override
                    public void onResponse(Call call, final Response response) throws IOException {
                        Log.d(TAG, "Got Media Response");
                        String req_url = response.request().url().toString();
                        String sreq_url = Util.clearQuery(req_url);
                        Map<String, Object> map = hlsStack.get(orig_url);
                        String spath = (String) map.get("strip_url");

                        if (sreq_url.endsWith(".m3u8")) {
                            String res = response.body().string();
                            if (res.contains("#EXT-X-STREAM-INF")) {
                                String lines[] = res.split("\\r?\\n");
                                String resolution = "";
                                for (String line : lines) {
                                    if (line.contains("#EXT-X-STREAM-INF")) {
                                        String stripped = line.replace("#EXT-X-STREAM-INF:", "").trim();
                                        String[] metadata = stripped.split(",");
                                        Log.d(TAG, "Printing Metadata for Orig URL: " + orig_url + " URL: " + sreq_url);
                                        for (String meta : metadata) {
                                            Log.d(TAG, "Meta: " + meta);
                                            if(meta.contains("RESOLUTION")) {
                                                String[] res_string = meta.split("=");
                                                resolution = res_string[1];
                                                Log.d(TAG, "Resolution: " + resolution);
                                            }
                                        }
                                    } else if (!(resolution.equals(""))) {
                                        String res_url = line.trim();
                                        map = hlsStack.get(orig_url);
                                        String processed = Util.processM3U8Url(res_url, orig_url);
                                        map.put("res_" + resolution, processed);
                                        hlsStack.put(orig_url, map);
                                        streamStack.put(Util.clearQuery(processed), orig_url);
                                        resolution = "";
                                    }
                                }

                                for (String line : lines) {
                                    if (line.contains(".m3u8")) {
                                        Log.d(TAG, "Playlist URL: " + line);
                                        String final_url = Util.processM3U8Url(line, orig_url);
                                        Log.d(TAG, "Final Playlist URL: " + final_url);
                                        get_request(final_url, orig_url);
                                    }
                                }
                            } else if (res.contains("#EXTINF")) {
                                String lines[] = res.split("\\r?\\n");
                                Double total_duration = 0.0;
                                ArrayList<String> ts_files = new ArrayList<>();
                                int ts_flag = 0;
                                for (String line : lines) {
                                    if (line.contains("#EXTINF")) {
                                        try {
                                            Double duration = Double.parseDouble(line.replace("#EXTINF:", "").replace(",", "").trim());
                                            total_duration += duration;
                                        } catch (NumberFormatException e) {
                                            Log.e(TAG, e.toString());
                                        }
                                        ts_flag = 1;
                                    } else if (ts_flag == 1) {
                                        String final_url = Util.processM3U8Url(line, sreq_url);
                                        ts_files.add(final_url);
                                        ts_flag = 0;
                                    }
                                }
                                String formatted_duration = DateUtils.formatElapsedTime(total_duration.longValue());
                                Log.d(TAG, "Total Duration: " + formatted_duration + " for Orig URL: " + orig_url + " and URL: " + url);
                                m3u8_ts_map.put(sreq_url, ts_files);
//                                for (String ts: ts_files.toArray(new String[ts_files.size()])) {
//                                    Log.d(TAG, sreq_url + " : " + ts);
//                                }
                                map = hlsStack.get(orig_url);
                                map.put("duration", formatted_duration);
                                map.put("type", "m3u8");
                                hlsStack.put(orig_url, map);
                            }
                        } else if (spath.endsWith(".mp4") || spath.endsWith(".vid")) {
                            BufferedInputStream in = new BufferedInputStream(response.body().byteStream(), 128);
                            byte[] buffer = new byte[128];
                            int mvhd_idx = -1;
                            String mvhd = "6D766864";
                            String mdat = "6D646174";

                            in.read(buffer);
                            Log.d(TAG, "Reading Buffer");
                            byte[] mvhd_buf = new byte[mvhd.length() / 2];

                            for (int i = 0; i < mvhd_buf.length; i++) {
                                int index = i * 2;
                                int j = Integer.parseInt(mvhd.substring(index, index + 2), 16);
                                mvhd_buf[i] = (byte) j;
                            }
                            mvhd_idx = Util.indexOf(buffer, mvhd_buf);
                            Log.d(TAG, "Got MVHD Index");
                            if (mvhd_idx == -1) {
                                byte[] mdat_buf = new byte[mdat.length() / 2];
                                for (int i = 0; i < mvhd_buf.length; i++) {
                                    int index = i * 2;
                                    int j = Integer.parseInt(mdat.substring(index, index + 2), 16);
                                    mdat_buf[i] = (byte) j;
                                }

                                int mdat_idx = Util.indexOf(buffer, mdat_buf);
                                Log.d(TAG, "Got MDAT Index");
                                if (mdat_idx != -1) {
                                    StringBuilder sb = new StringBuilder();
                                    for (int i=mdat_idx - 4; i < mdat_idx; i++) {
                                        byte b = buffer[i];
                                        sb.append(String.format("%02X", b));
                                    }

                                    int mdat_len = Integer.parseInt(sb.toString(), 16);
                                    in.skip(mdat_len - 128);
                                    in.read(buffer);

                                    sb = new StringBuilder();
                                    for (int i=0; i<buffer.length; i++) {
                                        byte b = buffer[i];
                                        sb.append(String.format("%02X", b));
                                    }

                                    mvhd_idx = Util.indexOf(buffer, mvhd_buf);
                                }
                            }

                            mvhd_idx += 17;
                            Log.d(TAG, "Got MVHD Index 2");
                            StringBuilder sb = new StringBuilder();
                            for (int i=mvhd_idx; i<mvhd_idx+3; i++) {
                                byte b = buffer[i];
                                sb.append(String.format("%02X", b));
                            }

                            int time_scale = Integer.parseInt(sb.toString(), 16);
                            sb = new StringBuilder();
                            for (int i=mvhd_idx+4; i<mvhd_idx+7; i++) {
                                byte b = buffer[i];
                                sb.append(String.format("%02X", b));
                            }

                            int scaled_duration = Integer.parseInt(sb.toString(),16);
                            Double total_duration = ((double)scaled_duration) / ((double)time_scale);
                            String formatted_duration = DateUtils.formatElapsedTime(total_duration.longValue());
                            Log.d(TAG, "Orig URL: " + orig_url);

                            map = hlsStack.get(orig_url);
                            map.put("duration", formatted_duration);
                            map.put("type", "mp4");
                            hlsStack.put(orig_url, map);
                        } else {
                            try {
                                BufferedInputStream in = new BufferedInputStream(response.body().byteStream(), 128);
                                byte[] buffer = new byte[128];
                                int mvhd_idx = -1;
                                String mvhd = "6D766864";
                                String mdat = "6D646174";

                                in.read(buffer);
                                Log.d(TAG, "Reading Buffer");
                                byte[] mvhd_buf = new byte[mvhd.length() / 2];

                                for (int i = 0; i < mvhd_buf.length; i++) {
                                    int index = i * 2;
                                    int j = Integer.parseInt(mvhd.substring(index, index + 2), 16);
                                    mvhd_buf[i] = (byte) j;
                                }
                                mvhd_idx = Util.indexOf(buffer, mvhd_buf);
                                Log.d(TAG, "Got MVHD Index");
                                if (mvhd_idx == -1) {
                                    byte[] mdat_buf = new byte[mdat.length() / 2];
                                    for (int i = 0; i < mvhd_buf.length; i++) {
                                        int index = i * 2;
                                        int j = Integer.parseInt(mdat.substring(index, index + 2), 16);
                                        mdat_buf[i] = (byte) j;
                                    }

                                    int mdat_idx = Util.indexOf(buffer, mdat_buf);
                                    Log.d(TAG, "Got MDAT Index");
                                    if (mdat_idx != -1) {
                                        StringBuilder sb = new StringBuilder();
                                        for (int i=mdat_idx - 4; i < mdat_idx; i++) {
                                            byte b = buffer[i];
                                            sb.append(String.format("%02X", b));
                                        }

                                        int mdat_len = Integer.parseInt(sb.toString(), 16);
                                        in.skip(mdat_len - 128);
                                        in.read(buffer);

                                        sb = new StringBuilder();
                                        for (int i=0; i<buffer.length; i++) {
                                            byte b = buffer[i];
                                            sb.append(String.format("%02X", b));
                                        }

                                        mvhd_idx = Util.indexOf(buffer, mvhd_buf);
                                    }
                                }

                                mvhd_idx += 17;
                                Log.d(TAG, "Got MVHD Index 2");
                                StringBuilder sb = new StringBuilder();
                                for (int i=mvhd_idx; i<mvhd_idx+3; i++) {
                                    byte b = buffer[i];
                                    sb.append(String.format("%02X", b));
                                }

                                int time_scale = Integer.parseInt(sb.toString(), 16);
                                sb = new StringBuilder();
                                for (int i=mvhd_idx+4; i<mvhd_idx+7; i++) {
                                    byte b = buffer[i];
                                    sb.append(String.format("%02X", b));
                                }

                                int scaled_duration = Integer.parseInt(sb.toString(),16);
                                Double total_duration = ((double)scaled_duration) / ((double)time_scale);
                                String formatted_duration = DateUtils.formatElapsedTime(total_duration.longValue());
                                Log.d(TAG, "Orig URL: " + orig_url);

                                map = hlsStack.get(orig_url);
                                map.put("duration", formatted_duration);
                                map.put("type", "mp4");
                                hlsStack.put(orig_url, map);
                            } catch (Exception e) {
                                Log.e(TAG, e.toString());
                            }
                        }
                        stream_flag = 0;
                    }
                });
    }

    private ByteArrayInputStream getBitmapInputStream(Bitmap bitmap, Bitmap.CompressFormat compressFormat) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        bitmap.compress(compressFormat, 80, byteArrayOutputStream);
        byte[] bitmapData = byteArrayOutputStream.toByteArray();
        return new ByteArrayInputStream(bitmapData);
    }

    private void clearCookiesAndHistory() {
        webView.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.getSettings().setAppCacheEnabled(false);
        webView.clearHistory();
        webView.clearCache(true);
        CookieManager.getInstance().removeSessionCookies(null);
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        WebStorage.getInstance().deleteAllData();
    }

    private void switchMode() {
        String UA;
        if (view_mode.equals("mobile")) {
            UA = getString(R.string.desktop_UA);
            view_mode = getString(R.string.desktop);
        } else {
            UA = getString(R.string.mobile_UA);
            view_mode = getString(R.string.mobile);
        }
        webView.getSettings().setUserAgentString(UA);
        clearCookiesAndHistory();
        webView.reload();
    }

    private  boolean isWriteStoragePermissionGranted() {

        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            Log.v(TAG,"Permission is granted2");
        } else {

            Log.v(TAG,"Permission is revoked2");
            ActivityCompat.requestPermissions(this, PERMISSIONS_ALL, 2);
        }

        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            Log.v(TAG,"Permission is granted2");
            return true;
        } else {

            Log.v(TAG,"Permission is revoked2");
            ActivityCompat.requestPermissions(this, PERMISSIONS_ALL, 2);
            return false;
        }
    }

    private void displayRedirectDialog(String media_url) {
        String[] options = {"Allow (Session)", "Block (Session)", "Block (Forever)"};
        Uri requestUri = Uri.parse(media_url);
        String domain = requestUri.getHost().replace("www.", "").replace(".com", "");
        new MaterialAlertDialogBuilder(this)
                .setTitle("Redirection Settings")
                .setItems(options, (dialogInterface, selectedOption) -> {
                        Log.d(TAG, "Selected Option: " + selectedOption);
                        switch (selectedOption) {
                            case 0:
                                Log.d(TAG, "Got Permission, Redirecting to: " + media_url);
                                allowedRedirectDomains.add(domain);
                                current_url = media_url;
                                editText.setText(current_url);
                                goButton.performClick();
                                break;
                            case 1:
                                blockedRedirectDomains.add(domain);
                                break;
                            case 2:
                                blockedRedirectDomainsForever.add(domain);
                                sharedPrefEditor = sharedPref.edit();
                                sharedPrefEditor.putStringSet("blockedRedirectDomainsForever", new HashSet<>(blockedRedirectDomainsForever));
                                sharedPrefEditor.apply();
                                break;
                            default:
                                Log.d(TAG, "Invalid Option");
                        }
                })
        .show();
    }

    private void resolveMedia(String media_path, Uri media_uri) {
        Log.d(TAG, "Media Path: " + media_path);
        String domain = media_uri.getHost().replace("www.", "").replace(".com", "");
        Log.d(TAG, "Checking Domain for Ads: " + domain);
        if (markedAdDomains.contains(domain)) {
            if (ad_parsing_flag)
                mediaRequests.addLast(media_path);
        }
        else
            mediaRequests.addFirst(media_path);
    }

    @JavascriptInterface
    public void onData(String value) {
        Log.d(TAG, "Returned Value: " + value);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web_view);

        CookieManager.getInstance().setAcceptCookie(true);

        executor = new Executor() {
            @Override
            public void execute(Runnable command) {
                new Thread(command).start();
            }
        };

        bubble_text = findViewById(R.id.bubble_text);
        hlsStack = new LinkedHashMap<>();
        streamStack = new LinkedHashMap<>();
        m3u8_ts_map = new LinkedHashMap<>();
        allRequests = new ConcurrentLinkedQueue<>();
        mediaRequests = new ConcurrentLinkedDeque<>();
        allowedRedirectDomains = new HashSet<>();
        blockedRedirectDomains = new HashSet<>();
        sharedPref = this.getPreferences(Context.MODE_PRIVATE);
        blockedRedirectDomainsForever = new HashSet<String>(sharedPref.getStringSet("blockedRedirectDomainsForever", new HashSet<>()));
        markedAdDomains =  new HashSet<String>(sharedPref.getStringSet("markedAdDomains", new HashSet<>()));
        Log.d(TAG, "Checking Marked Ad Domains");
        for (String domain : markedAdDomains) {
            Log.d(TAG, "Marked Ad Domain Init: " + domain);
        }
        ad_parsing_flag = sharedPref.getBoolean("ad_parsing_flag", true);
        Log.d(TAG, "Ad Parsing Flag Init: " + ad_parsing_flag);
        ad_blocking_flag = sharedPref.getBoolean("ad_blocking_flag", false);

        isWriteStoragePermissionGranted();

        Intent startServiceIntent = new Intent(WebViewActivity.this, HLSService.class);
        startServiceIntent.setAction("ACTION_START_SERVICE");
        startForegroundService(startServiceIntent);

        h1 = new Handler();
        r1 = new Runnable() {
            int count = 1;
            @Override
            public void run() {
                count++;
                int bubble_size = 1;
                for (Map.Entry<String, Map<String, Object>> mapElement : hlsStack.entrySet()) {
                    String key = mapElement.getKey();
                    Map<String, Object> value = mapElement.getValue();
                    String duration = value.get("duration").toString();
                    if (duration.equals("00:00")) continue;
                    bubble_size++;
                }
                bubble_text.setText(String.valueOf(bubble_size));
                if(webView.getUrl() != null && !(current_url.equals(webView.getUrl()))) {
                    current_url = webView.getUrl();
                    if(!(editText.getText().equals(current_url))) editText.setText(current_url);
                }
                h1.postDelayed(this, 200); //ms
            }
        };
        h1.postDelayed(r1, 5000);

        h2 = new Handler();
        r2 = new Runnable() {
            int count = 0;
            @Override
            public void run() {
                count++;
                int req_size = mediaRequests.size();
                if (req_size > 0) {
                    String media_path = mediaRequests.poll();
                    if (media_path != null) {
                        String spath = Util.clearQuery(media_path);
                        if(spath.endsWith(".m3u8") && stream_flag==1) {
                            mediaRequests.add(media_path);
                            h2.postDelayed(this, 1000); //ms
                            return;
                        }
                        Log.d(TAG, "Poll Media Request: " + media_path);
                        get_request(media_path, media_path);
                        Log.d(TAG, "Stream Stack: " + String.join(System.getProperty("line.separator") ,streamStack.keySet().toArray(new String[streamStack.keySet().size()])));
                    }
                }
                h2.postDelayed(this, 1000); //ms
            }
        };
        h2.postDelayed(r2, 5000); // one second in ms

        editText = findViewById(R.id.web_address_edit_text);
        back = findViewById(R.id.back_arrow);
        forward = findViewById(R.id.forward_arrow);
        stop = findViewById(R.id.stop);
        goButton = findViewById(R.id.go_button);
        refresh = findViewById(R.id.refresh);
        homeButton = findViewById(R.id.home);
        menuButton = findViewById(R.id.menu);
        streamButton = findViewById(R.id.stream);
        downloadButton = findViewById(R.id.download);
        optionsButton = findViewById(R.id.options);
        progressBar = findViewById(R.id.progress_bar);
        progressBar.setMax(100);
        progressBar.setVisibility(View.VISIBLE);
        webView = findViewById(R.id.web_view);

        goButton.setOnClickListener(this);
        back.setOnClickListener(this);
        forward.setOnClickListener(this);
        stop.setOnClickListener(this);
        refresh.setOnClickListener(this);
        homeButton.setOnClickListener(this);
        menuButton.setOnClickListener(this);
        streamButton.setOnClickListener(this);
        optionsButton.setOnClickListener(this);
        downloadButton.setOnClickListener(this);


        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setLoadWithOverviewMode(true);
            settings.setUseWideViewPort(true);
            settings.setSupportZoom(true);
            settings.setBuiltInZoomControls(true);
            settings.setDisplayZoomControls(false);
            settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
            settings.setDomStorageEnabled(true);
            String UA = getString(R.string.mobile_UA);
            settings.setUserAgentString(UA);
            webView.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
            webView.setBackgroundColor(Color.WHITE);
            webView.setScrollbarFadingEnabled(true);
            webView.addJavascriptInterface(this, "android");
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

            clearCookiesAndHistory();

            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onProgressChanged(WebView view, int newProgress) {
                    super.onProgressChanged(view, newProgress);
                    progressBar.setProgress(newProgress);
                    if (newProgress < 100 && progressBar.getVisibility() == ProgressBar.GONE) {
                        progressBar.setVisibility(ProgressBar.VISIBLE);
                    }
                    if (newProgress == 100) {
                        progressBar.setVisibility(ProgressBar.GONE);

                    }else{
                        progressBar.setVisibility(ProgressBar.VISIBLE);
                    }
                }

                @Override
                public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                    result.cancel();
                    return true;
                }

                @Override
                public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                    result.cancel();
                    return true;
                }

                @Override
                public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                    result.cancel();
                    return true;
                }

                @Override
                public boolean onConsoleMessage(ConsoleMessage cm) {
                    Log.d(TAG, "Console Message: " + cm.message());
                    return true;
                }

                });
            }

        webView.setWebViewClient(new MyWebViewClient() {
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    String path = request.getUrl().toString().trim();
                    String domain = request.getUrl().getHost().replace("www.", "").replace(".com", "");
                    if (ad_blocking_flag && markedAdDomains.contains(domain)) {
                        Log.d(RequestTAG, "Blocking Ad with URL: " + path);
                        return new WebResourceResponse(
                                "text/html",
                                "UTF-8",
                                null
                        );
                    }
                    String spath = Util.clearQuery(path);
                    String lpath = path.toLowerCase();
                    allRequests.add(path);
                    Log.d(RequestTAG, path);
//                    for (String header_key : request.getRequestHeaders().keySet()) Log.d(RequestTAG, header_key + ": " + request.getRequestHeaders().get(header_key));
                    if (spath.endsWith(".m3u8") || spath.endsWith(".mp4") || spath.endsWith(".vid") && !(spath.endsWith(".ts")) || (request.getRequestHeaders().containsKey("Range"))) {
                        if (!(hlsStack.containsKey(path))) {
                            Log.d(TAG, "New Media Url: " + path);
                            resolveMedia(path, request.getUrl());
                        }
                    } else if (lpath.contains(".jpg") || lpath.contains(".jpeg")) {
                        Bitmap bitmap = Glide.with(view).asBitmap().diskCacheStrategy(DiskCacheStrategy.ALL).load(path).submit().get();
                        return new WebResourceResponse("image/jpg", "UTF-8", getBitmapInputStream(bitmap, Bitmap.CompressFormat.JPEG));
                    } else if (lpath.contains(".png")) {
                        Bitmap bitmap = Glide.with(view).asBitmap().diskCacheStrategy(DiskCacheStrategy.ALL).load(path).submit().get();
                        return new WebResourceResponse("image/png", "UTF-8", getBitmapInputStream(bitmap, Bitmap.CompressFormat.PNG));
                    } else if (lpath.contains(".webp")) {
                        Bitmap bitmap = Glide.with(view).asBitmap().diskCacheStrategy(DiskCacheStrategy.ALL).load(path).submit().get();
                        return new WebResourceResponse("image/webp", "UTF-8", getBitmapInputStream(bitmap, Bitmap.CompressFormat.WEBP));
                    } else {
                        return super.shouldInterceptRequest(view, request);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return super.shouldInterceptRequest(view, request);
            }

            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri requestUri = request.getUrl();
                String domain = requestUri.getHost().replace("www.", "").replace(".com", "");
                String path = requestUri.toString();
                Log.d("HLS_CurrentURL", requestUri.getHost());
                Log.d("HLS_CurrentURL", "Current URL: " + current_url);
                Uri curl = Uri.parse(current_url.trim());

                if (!(requestUri.getHost().contains(curl.getHost().replace("www.", "").replace(".com", "")) || curl.getHost().equals(getString(R.string.domain_google)) || allowedRedirectDomains.contains(requestUri.getHost().replace("www.", "").replace(".com", "")))) {
                    String spath = Util.clearQuery(path);
                    if (spath.endsWith(".m3u8") || spath.endsWith(".mp4") || spath.endsWith(".vid") && !(spath.endsWith(".ts"))) {
                        if (!(hlsStack.containsKey(path))) {
                            Log.d(TAG, "New Media Url: " + path);
                            resolveMedia(path, requestUri);
                        }
                    }
                    Log.d(TAG, "Avoiding Redirect: " + path);
                    if (!(allowedRedirectDomains.contains(domain) || blockedRedirectDomains.contains(domain) || blockedRedirectDomainsForever.contains(domain))) {
                        Snackbar redirectSnackbar = Snackbar.make(view, "Avoiding Redirect to " + requestUri.getHost(), BaseTransientBottomBar.LENGTH_LONG);
                        redirectSnackbar.setAction("Redirect Settings", new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                displayRedirectDialog(path);
                            }
                        });
                        redirectSnackbar.show();
                    }
                    return true;
                }
                Log.d(TAG, "Redirecting to: " + path);
                current_url = path;
                editText.setText(current_url);
                goButton.performClick();
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "Page Finished loading: " + url);
                long end_time = System.currentTimeMillis();
                Log.d(TAG, "Page Time Taken: " + (end_time - current_page_time) / 1000.0f);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                Log.d(TAG, "Page Started loading: " + url);
                current_page_time = System.currentTimeMillis();
            }
        });


        editText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if ((event != null && (event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) || (actionId == EditorInfo.IME_ACTION_DONE) ) {
                    Log.d(TAG,"Enter pressed");
                    goButton.performClick();
                }
                return false;
            }
        });

        OnBackPressedCallback callback = new OnBackPressedCallback(true /* enabled by default */) {
            @Override
            public void handleOnBackPressed() {
                Log.d(TAG, "Back Button Pressed");
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        };

        WebViewActivity.this.getOnBackPressedDispatcher().addCallback(WebViewActivity.this, callback);

        Intent intent = getIntent();
        String action = intent.getAction();
        Bundle bundle = intent.getExtras();

        if(action != null) {
            Log.d(TAG, "Action: " + action);
            if (action.equals("android.intent.action.SEND")) {
                String url = bundle.getString("android.intent.extra.TEXT");
                Log.d(TAG, "Intent Uri: " + url);
                editText.setText(url);
                goButton.performClick();
            }
        }
    }


    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String action = intent.getAction();
        Bundle bundle = intent.getExtras();

        Log.d(TAG, "Action: " + action);
        if (action.equals("android.intent.action.SEND")) {
            String url = bundle.getString("android.intent.extra.TEXT");
            Log.d(TAG, "Intent Uri: " + url);
            editText.setText(url);
            goButton.performClick();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        clearCookiesAndHistory();
        CookieManager.getInstance().setAcceptCookie(false);
        Log.d(TAG, "WebView Destroyed!");
        h1.removeCallbacksAndMessages(r1);
        h2.removeCallbacksAndMessages(r2);
    }

    public void onClick(View v) {

        Intent intent;

        switch (v.getId()) {
            case R.id.go_button:
                try {
                    if(!NetworkState.connectionAvailable(WebViewActivity.this)){
                        Toast.makeText(WebViewActivity.this, R.string.check_connection, Toast.LENGTH_SHORT).show();
                    }else {

                        InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                        inputMethodManager.hideSoftInputFromWindow(editText.getWindowToken(), 0);
                        current_url = editText.getText().toString();
                        if (!(current_url.startsWith("http://") || current_url.startsWith("https://"))) {
                            current_url = "https://" + current_url;
                        }
                        editText.setText(current_url);
                        webView.loadUrl(current_url);
                    }

                }catch (Exception e) {
                    e.printStackTrace();
                }
                break;
            case R.id.back_arrow:
                if (webView.canGoBack()) {
                    webView.goBack();
                }
                break;
            case R.id.forward_arrow:
                if (webView.canGoForward()) {
                    webView.goForward();
                }
                break;

            case R.id.stop:
                webView.stopLoading();
                break;

            case R.id.refresh:
                webView.reload();
                break;

            case R.id.home:
                current_url = "https://" + getString(R.string.domain_google);
//                current_url = "https://content.jwplatform.com/manifests/yp34SRmf.m3u8";
//                current_url = "https://file-examples-com.github.io/uploads/2017/04/file_example_MP4_1920_18MG.mp4";
//                current_url = "https://sample-videos.com/video123/mp4/720/big_buck_bunny_720p_30mb.mp4";
                webView.loadUrl(current_url);
                break;

            case R.id.menu:
                final PopupMenu menu = new PopupMenu(WebViewActivity.this, v);
                final PopupMenu sec_menu = new PopupMenu(WebViewActivity.this, v);
                final PopupMenu tri_menu = new PopupMenu(WebViewActivity.this, v);
                Map<String, String> res_map = new LinkedHashMap<>();
                String[] file_name = new String[1];

                menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    public boolean onMenuItemClick(MenuItem item) {
                        sec_menu.getMenu().clear();
                        String item_title = (String) item.getTitle();

                        if (item_title.contains("<1 Min Videos")) {
                            min1_flag = min1_flag ^ 1;
                            return true;
                        } else if (item_title.equals("Parse Current URL")) {
                            if (current_url != null && current_url.length() > 0) {
                                executor.execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            VideoInfo streamInfo = YoutubeDL.getInstance().getInfo(current_url);
                                            file_name[0] = streamInfo.getTitle().replace(" ", "_");
                                            if (file_name[0].length() > 40) {
                                                String new_name = file_name[0];
                                                int index = new_name.indexOf("_");
                                                while(index >= 0) {
                                                    int new_index = new_name.indexOf("_", index + 1);
                                                    if (new_index > 40 || new_index < 0) break;
                                                    index = new_index;
                                                }
                                                file_name[0] = new_name.substring(0, index);
                                            }
                                            ArrayList<VideoFormat> vf = streamInfo.getFormats();
                                            res_map.put("Audio Only", "bestaudio");
                                            for(VideoFormat format: vf) {
                                                if(format.getWidth() != 0 && format.getExt().equals("mp4")) {
                                                    String res = format.getWidth() + "x" + format.getHeight();
                                                    res_map.put(res, format.getFormatId());
                                                }

                                            }
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                        }
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                for(String res : res_map.keySet()) {
                                                    tri_menu.getMenu().add(res);
                                                }
                                                tri_menu.show();
                                            }
                                        });
                                    }
                                });
                                Toast.makeText(WebViewActivity.this, "Parsing URL!", Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(WebViewActivity.this, "Empty URL!", Toast.LENGTH_LONG).show();
                            }
                        } else {
                            selected_path = item_title.substring(item_title.indexOf("] ")+1).trim();

                            Map<String, Object> map = hlsStack.get(selected_path);
                            if (map == null) {
                                Log.d(TAG, "Map Null!!!!");
                                return true;
                            }
                            for (Map.Entry<String, Object> mapElement : map.entrySet()) {
                                String key = mapElement.getKey();
                                String value = mapElement.getValue().toString();
                                Log.d(TAG, key + ": " + value);
                                if (key.contains("res_")) {
                                    String resolution = key.replace("res_", "").trim();
                                    res_map.put(resolution, value);
                                    sec_menu.getMenu().add(resolution);
                                }
                            }
                            if (res_map.size() > 0) {
                                sec_menu.show();
                                return true;
                            }
                            Toast.makeText(WebViewActivity.this, (String) map.get("strip_url"), Toast.LENGTH_LONG).show();
                        }
                        return true;
                    }
                });

                sec_menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        String item_title = (String) item.getTitle();
                        selected_path = res_map.get(item_title.trim());
                        for (String key: m3u8_ts_map.keySet()) {
                            Log.d(TAG, "MAP KEY: " + key + " TS FILES: " + m3u8_ts_map.get(key).size());
                        }
                        Toast.makeText(WebViewActivity.this,selected_path, Toast.LENGTH_LONG).show();
                        return true;
                    }
                });

                tri_menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        String item_title = (String) item.getTitle();
                        String format_id = res_map.get(item_title.trim());

                        Intent intent = new Intent(WebViewActivity.this, HLSService.class);
                        intent.putExtra("url", current_url);
                        intent.putExtra("file", file_name[0]);
                        intent.putExtra("type", "yt");
                        intent.putExtra("format_id", format_id);
                        intent.setAction("ACTION_ENQUEUE");
                        startForegroundService(intent);
                        Toast.makeText(WebViewActivity.this, "Download Started!", Toast.LENGTH_LONG).show();
                        return true;
                    }
                });

                menu.getMenu().add("Parse Current URL");
                for (Map.Entry<String, Map<String, Object>> mapElement : hlsStack.entrySet()) {
                    String key = mapElement.getKey();
                    Map<String, Object> value = mapElement.getValue();
                    String duration = value.get("duration").toString();
                    if (duration.equals("00:00")) continue;
                    if (min1_flag == 0 && !(duration.equals(""))) {
                        if (Integer.parseInt(duration.split(":")[0]) == 0) continue;
                    }
                    menu.getMenu().add(("["+duration+"] "+key).trim());
                }
                Map<Integer, String> map = new LinkedHashMap<>();
                map.put(0, "Show");
                map.put(1, "Hide");
                menu.getMenu().add(map.get(min1_flag) + " <1 Min Videos");
                menu.show();
                break;

            case R.id.stream:
                if (selected_path.length() > 0) {
                    intent = new Intent(WebViewActivity.this, Exoplayer.class);
                    intent.putExtra("path", selected_path);
                    startActivity(intent);
                } else {
                    Toast.makeText(WebViewActivity.this, "No URL Selected!", Toast.LENGTH_LONG).show();
                }
                break;

            case R.id.download:
                if(selected_path.length() > 0) {
                    File dir_downloads = Environment.getExternalStorageDirectory();
                    String downloads = dir_downloads.getAbsolutePath() + "/Download/";
                    String file_hash = Util.getMD5EncryptedString(selected_path);

                    intent = new Intent(WebViewActivity.this, HLSService.class);
                    intent.putExtra("url", selected_path);
                    intent.putExtra("file", downloads + file_hash + ".mp4");
                    String cleared_path = Util.clearQuery(selected_path);
                    if (cleared_path.endsWith(".m3u8")) {
                        Log.d(TAG, "Cleared Path: " + cleared_path + " TS FILES: " + m3u8_ts_map.get(cleared_path).size());
                        intent.putExtra("type", "m3u8");
                        intent.putStringArrayListExtra("ts_files", m3u8_ts_map.get(cleared_path));
                    } else {
                        intent.putExtra("type", "mp4");
                    }
                    intent.setAction("ACTION_ENQUEUE");
                    startForegroundService(intent);
                    Toast.makeText(WebViewActivity.this, "Download Started!", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(WebViewActivity.this, "No URL Selected!", Toast.LENGTH_LONG).show();
                }
                break;
            case R.id.options:
                final PopupMenu settingsMenu = new PopupMenu(WebViewActivity.this, v);
                settingsMenu.getMenu().clear();
                settingsMenu.getMenu().add("Wipe Cache");
                settingsMenu.getMenu().add("Desktop Mode");
                settingsMenu.getMenu().add("Mark Media URL as Ad");
                if (ad_parsing_flag)
                    settingsMenu.getMenu().add("Disable Ad Parsing");
                else
                    settingsMenu.getMenu().add("Enable Ad Parsing");
                if (ad_blocking_flag)
                    settingsMenu.getMenu().add("Unblock Ads");
                else
                    settingsMenu.getMenu().add("Block Ads");
                settingsMenu.getMenu().add("Logs");
                settingsMenu.show();

                settingsMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        switch (item.getTitle().toString()) {
                            case "Wipe Cache":
                                clearCookiesAndHistory();
                                webView.reload();
                                break;
                            case "Desktop Mode":
                                switchMode();
                                break;
                            case "Mark Media URL as Ad":
                                Uri media_uri = Uri.parse(selected_path);
                                String domain = media_uri.getHost().replace("www.", "").replace(".com", "");
                                markedAdDomains.add(domain);
                                sharedPrefEditor = sharedPref.edit();
                                sharedPrefEditor.putStringSet("markedAdDomains", new HashSet<>(markedAdDomains));
                                sharedPrefEditor.apply();
                                Log.d(TAG, "Marked Ad Domain: " + domain);
                                break;
                            case "Disable Ad Parsing":
                                ad_parsing_flag = false;
                                sharedPrefEditor = sharedPref.edit();
                                sharedPrefEditor.putBoolean("ad_parsing_flag", ad_parsing_flag);
                                sharedPrefEditor.apply();
                                break;
                            case "Enable Ad Parsing":
                                ad_parsing_flag = true;
                                sharedPrefEditor = sharedPref.edit();
                                sharedPrefEditor.putBoolean("ad_parsing_flag", ad_parsing_flag);
                                sharedPrefEditor.apply();
                                break;
                            case "Block Ads":
                                ad_blocking_flag = true;
                                sharedPrefEditor = sharedPref.edit();
                                sharedPrefEditor.putBoolean("ad_blocking_flag", ad_blocking_flag);
                                sharedPrefEditor.apply();
                                break;
                            case "Unblock Ads":
                                ad_blocking_flag = false;
                                sharedPrefEditor = sharedPref.edit();
                                sharedPrefEditor.putBoolean("ad_blocking_flag", ad_blocking_flag);
                                sharedPrefEditor.apply();
                                break;
                            case "Logs":
                                Intent intent1 = new Intent(WebViewActivity.this, AppLogs.class);
                                startActivity(intent1);
                                break;
                            default:
                                return false;
                        }
                        return true;
                    }
                });
                break;
            default:
                break;
        }
    }

}


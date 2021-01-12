package com.example.hls;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.app.ActivityCompat;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class WebViewActivity extends AppCompatActivity implements View.OnClickListener {

    WebView webView;
    EditText editText;
    ProgressBar progressBar;
    ImageButton back, forward, stop, refresh, homeButton, menuButton, streamButton, viewModeButton, downloadButton;
    Button goButton;
    Map<String, Map<String, Object>> hlsStack;
    Map<String, String> streamStack;
    Map<String, ArrayList<String>> m3u8_ts_map;
    Queue<String> allRequests, mediaRequests;
    String selected_path, current_url = "", view_mode = "mobile", TAG="WebView", RequestTAG="Request_WebView", okhttpTAG = "Okhttp_WebView";
    TextView bubble_text;
    int redirect_flag = 0, stream_flag = 0, min1_flag = 0;

    Handler h1,h2;
    Runnable r1,r2;

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

        client.newCall(request)
                .enqueue(new Callback() {
                    @Override
                    public void onFailure(final Call call, IOException e) {
                        // Error

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                // For the example, you can show an error dialog or a toast
                                // on the main UI thread
                            }
                        });
                    }

                    @Override
                    public void onResponse(Call call, final Response response) throws IOException {
                        String req_url = response.request().url().toString();
                        String sreq_url = Util.clearQuery(req_url);
                        Map<String, Object> map = hlsStack.get(orig_url);
                        String spath = (String) map.get("strip_url");
//                        Log.d(okhttpTAG, "Playlist: \n" + res);
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
                                        //                                    Log.d(okhttpTAG, "Segments: " + line);
                                        Double duration = Double.parseDouble(line.replace("#EXTINF:", "").replace(",", "").trim());
                                        total_duration += duration;
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
                                for (String ts: ts_files.toArray(new String[ts_files.size()])) {
                                    Log.d(TAG, sreq_url + " : " + ts);
                                }
                                map = hlsStack.get(orig_url);
                                map.put("duration", formatted_duration);
                                map.put("type", "m3u8");
                                hlsStack.put(orig_url, map);
                            }
                        } else if (spath.endsWith(".mp4") || spath.endsWith(".vid")) {
//                            Log.d(okhttpTAG, response.headers().toString());
                            BufferedInputStream in = new BufferedInputStream(response.body().byteStream(), 2048);
                            byte[] buffer = new byte[4096];
                            in.read(buffer);
//                            StringBuilder sb = new StringBuilder();
//                            for (byte b : buffer) {
//                                sb.append(String.format("%02X ", b));
//                            }
//                            Log.d(TAG, "HEX Bytes: " + sb.toString());
                            String mvhd = "6D766864";
                            byte[] mvhd_buf = new byte[mvhd.length() / 2];
                            for (int i = 0; i < mvhd_buf.length; i++) {
                                int index = i * 2;
                                int j = Integer.parseInt(mvhd.substring(index, index + 2), 16);
                                mvhd_buf[i] = (byte) j;
                            }
                            int mvhd_idx = Util.indexOf(buffer, mvhd_buf) + 17;
                            StringBuilder sb = new StringBuilder();
                            for (int i=mvhd_idx; i<mvhd_idx+3; i++) {
                                byte b = buffer[i];
                                sb.append(String.format("%02X", b));
                            }
                            int time_scale = Integer.parseInt(sb.toString(), 16);
//                            Log.d(TAG, "Time Scale: " + time_scale);
                            sb = new StringBuilder();
                            for (int i=mvhd_idx+4; i<mvhd_idx+7; i++) {
                                byte b = buffer[i];
                                sb.append(String.format("%02X", b));
                            }
                            int scaled_duration = Integer.parseInt(sb.toString(),16);
                            Double total_duration = ((double)scaled_duration) / ((double)time_scale);
                            String formatted_duration = DateUtils.formatElapsedTime(total_duration.longValue());
//                            Log.d(TAG, "Total Duration: " + formatted_duration);
                            Log.d(TAG, "Orig URL: " + orig_url);
                            map = hlsStack.get(orig_url);
                            map.put("duration", formatted_duration);
                            map.put("type", "mp4");
                            hlsStack.put(orig_url, map);
                        }
                        // Do something with the response
                        stream_flag = 0;
                    }
                });
    }


    private void clearCookiesAndHistory() {
        CookieManager.getInstance().setAcceptCookie(false);
        webView.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.getSettings().setAppCacheEnabled(false);
        webView.clearHistory();
        webView.clearCache(true);
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
//                return true;
        } else {

            Log.v(TAG,"Permission is revoked2");
            ActivityCompat.requestPermissions(this, PERMISSIONS_ALL, 2);
//                return false;
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

    private void resolveMedia(String media_path) {
        Log.d(TAG, "Media Path: " + media_path);
        mediaRequests.add(media_path);
    }

    @JavascriptInterface
    public void onData(String value) {
        //.. do something with the data
        Log.d(TAG, "Returned Value: " + value);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web_view);

        bubble_text = findViewById(R.id.bubble_text);
        hlsStack = new LinkedHashMap<>();
        streamStack = new LinkedHashMap<>();
        m3u8_ts_map = new LinkedHashMap<>();
        allRequests = new ConcurrentLinkedQueue<>();
        mediaRequests = new ConcurrentLinkedQueue<>();

        isWriteStoragePermissionGranted();

        Intent startServiceIntent = new Intent(WebViewActivity.this, HLSService.class);
        startServiceIntent.setAction("ACTION_START_SERVICE");
        startForegroundService(startServiceIntent);

//        ContentResolver resolver = getContentResolver();
//        ContentValues contentValues = new ContentValues();
//        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, "file.txt");
//        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
//        contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
////        contentValues.put(MediaStore.MediaColumns.DATA, "Yo!");
//
//        Uri uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues);
//        try {
//            OutputStream outputStream = resolver.openOutputStream(uri);
//            outputStream.write("Yo!".getBytes());
//            outputStream.close();
//            Log.i(TAG, "File Written!");
//        } catch (FileNotFoundException e) {
//            Log.e(TAG, "File Not Found!");
//            Log.e(TAG, e.toString());
//        } catch (IOException e) {
//            Log.e(TAG, "IO Exception!");
//            Log.e(TAG, e.toString());
//        }

        h1 = new Handler();
        r1 = new Runnable() {
            int count = 0;
            @Override
            public void run() {
                count++;
                bubble_text.setText(String.valueOf(hlsStack.size()));
                h1.postDelayed(this, 1000); //ms
            }
        };
        h1.postDelayed(r1, 5000); // one second in ms

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

//        resolveMedia("https://file-examples-com.github.io/uploads/2017/04/file_example_MP4_480_1_5MG.mp4");

        editText = findViewById(R.id.web_address_edit_text);
        back = findViewById(R.id.back_arrow);
        forward = findViewById(R.id.forward_arrow);
        stop = findViewById(R.id.stop);
        goButton = findViewById(R.id.go_button);
        refresh = findViewById(R.id.refresh);
        homeButton = findViewById(R.id.home);
        menuButton = findViewById(R.id.menu);
        streamButton = findViewById(R.id.stream);
        viewModeButton = findViewById(R.id.view_mode);
        downloadButton = findViewById(R.id.download);
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
        viewModeButton.setOnClickListener(this);
        downloadButton.setOnClickListener(this);


        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setUseWideViewPort(true);
            webView.getSettings().setLoadWithOverviewMode(true);
            webView.getSettings().setSupportZoom(true);
            webView.getSettings().setSupportMultipleWindows(true);
            webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
            webView.setBackgroundColor(Color.WHITE);
            webView.getSettings().setLoadWithOverviewMode(true);
            webView.getSettings().setUseWideViewPort(true);
            webView.getSettings().setSupportZoom(true);
            webView.getSettings().setBuiltInZoomControls(true);
            webView.getSettings().setDisplayZoomControls(false);
            webView.setScrollbarFadingEnabled(false);
            String UA = getString(R.string.mobile_UA);
            webView.getSettings().setUserAgentString(UA);
            webView.addJavascriptInterface(this, "android");

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
                String path = request.getUrl().toString().trim();
                String spath = Util.clearQuery(path);
                allRequests.add(path);
                Log.d(RequestTAG, path);
                if (spath.endsWith(".m3u8") || spath.endsWith(".mp4") || spath.endsWith(".vid") && !(spath.endsWith(".ts"))) {
                    if (!(hlsStack.containsKey(path))) {
                        Log.d(TAG, "New Media Url: " + path);
                        resolveMedia(path);
                    }

                }
                return super.shouldInterceptRequest(view, request);
            }

            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (redirect_flag==1 && !(request.getUrl().toString().equals(view.getOriginalUrl()))) current_url = request.getUrl().getHost();
                redirect_flag = 0;
                Log.d("WebView_CurrentURL", request.getUrl().getHost());
                Log.d("WebView_CurrentURL", "Current URL: " + current_url);
                if (!(current_url.contains(request.getUrl().getHost()) || current_url.equals(getString(R.string.domain_google)))) {
                    return false;
                }
                current_url = request.getUrl().getHost();
                editText.setText(request.getUrl().toString());
                return super.shouldOverrideUrlLoading(view, request);


            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "Page Finished loading: " + url);
//                view.loadUrl("javascript:document.addEventListener('click', function(e) {\n" +
//                        "    e = e || window.event; e.stopPropagation();e.preventDefault();\n" +
//                        "    var target = e.target || e.srcElement,\n" +
//                        "        text = target.textContent || target.innerText; console.log(target.outerHTML);\n" +
//                        "}, false);");
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
                // Handle the back button event
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
        Log.d("WebView", "Destroyed!");
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
                        redirect_flag = 1;
                        webView.loadUrl(current_url);
//                        editText.setText("");
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
                current_url = getString(R.string.domain_google);
//                current_url = "https://content.jwplatform.com/manifests/yp34SRmf.m3u8";
//                current_url = "https://file-examples-com.github.io/uploads/2017/04/file_example_MP4_1920_18MG.mp4";
//                current_url = "https://sample-videos.com/video123/mp4/720/big_buck_bunny_720p_30mb.mp4";
                webView.loadUrl(current_url);
                break;

            case R.id.menu:
                final PopupMenu menu = new PopupMenu(WebViewActivity.this, v);
                final PopupMenu sec_menu = new PopupMenu(WebViewActivity.this, v);
                Map<String, String> res_map = new LinkedHashMap<>();

//                menu.getMenu().add("Three");
                menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    public boolean onMenuItemClick(MenuItem item) {
                        sec_menu.getMenu().clear();
                        String item_title = (String) item.getTitle();
                        if (item_title.contains("<1 Min Videos")) {
                            min1_flag = min1_flag ^ 1;
                            return true;
                        }
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
                intent = new Intent(WebViewActivity.this, Exoplayer.class);
                intent.putExtra("path", selected_path);
                startActivity(intent);
                break;

            case R.id.view_mode:
                switchMode();
                break;

            case R.id.download:
                if(selected_path!=null) {
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
                }
                break;

            default:
                break;
        }
    }

}


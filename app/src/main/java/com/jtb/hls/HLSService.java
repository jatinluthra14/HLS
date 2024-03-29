package com.jtb.hls;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.StrictMode;
import android.text.format.DateUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.arthenica.mobileffmpeg.Config;
import com.arthenica.mobileffmpeg.ExecuteCallback;
import com.arthenica.mobileffmpeg.FFmpeg;
import com.tonyodev.fetch2.Download;
import com.tonyodev.fetch2.Error;
import com.tonyodev.fetch2.Fetch;
import com.tonyodev.fetch2.FetchConfiguration;
import com.tonyodev.fetch2.FetchListener;
import com.tonyodev.fetch2.HttpUrlConnectionDownloader;
import com.tonyodev.fetch2.NetworkType;
import com.tonyodev.fetch2.Priority;
import com.tonyodev.fetch2.Request;
import com.tonyodev.fetch2.Status;
import com.tonyodev.fetch2core.DownloadBlock;
import com.tonyodev.fetch2core.Downloader;
import com.tonyodev.fetch2core.Func;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLException;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static androidx.core.app.NotificationCompat.PRIORITY_MIN;
import static com.arthenica.mobileffmpeg.Config.RETURN_CODE_CANCEL;
import static com.arthenica.mobileffmpeg.Config.RETURN_CODE_SUCCESS;

public class HLSService extends Service {

    public static final String ACTION_START_SERVICE = "ACTION_START_SERVICE";
    public static final String ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE";
    public static final String ACTION_ENQUEUE = "ACTION_ENQUEUE";
    public static final String ACTION_RESUME = "ACTION_RESUME";
    public static final String ACTION_PAUSE = "ACTION_PAUSE";
    public static final String ACTION_CANCEL = "ACTION_CANCEL";

    String TAG = "HLS_Service", NOTIF_TAG = "HLS_Notif", channel_id = "Download";
    NotificationManager notificationManager;
    NotificationCompat.Builder notificationBuilder;
    int dur_flag = 0, final_duration = 0;
    String duration = "";
    long currentMillis;
    private Fetch fetch;
    private static DecimalFormat df = new DecimalFormat("0.00");
    Set<Integer> notif_ids;
    FetchListener fetchListener;
    int max_retries = 5;
    Map<Integer, Integer> retries;
    String[] ts_names;
    Map<Integer, Map<Integer, Map<String, Long>>> groups;
    Map<Integer ,Map<String, Object>> group_files;
    Map<Integer, Long> groupstartTimes;
    Handler h;
    Executor executor;
    Runnable groupProgressThread;

    @Override
    public void onCreate() {
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);
        h = new Handler();
        executor = new Executor() {
            @Override
            public void execute(Runnable command) {
                new Thread(command).start();
            }
        };

        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    YoutubeDL.getInstance().init(getApplication());
                    YoutubeDL.getInstance().updateYoutubeDL(getApplication());
                    com.yausername.ffmpeg.FFmpeg.getInstance().init(getApplication());
                    Log.d(TAG, "YoutubeDL Version: " + YoutubeDL.getInstance().version(getApplicationContext()));
                } catch (YoutubeDLException e) {
                    e.printStackTrace();
                }
            }
        };

//        h.post(r);
        executor.execute(r);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel notificationChannel = new NotificationChannel(channel_id, "HLS Service", NotificationManager.IMPORTANCE_DEFAULT);

        // Configure the notification channel.
        notificationChannel.setDescription("HLS Download Progress");
        notificationChannel.enableLights(true);
        notificationChannel.setLightColor(Color.RED);
        notificationChannel.setVibrationPattern(new long[]{0, 1000, 500, 1000});
        notificationChannel.enableVibration(true);
        notificationManager.createNotificationChannel(notificationChannel);

        Intent stopServiceIntent = new Intent(this, HLSService.class);
        stopServiceIntent.setAction(ACTION_STOP_SERVICE);
        PendingIntent pendingPrevIntent = PendingIntent.getService(this, 0, stopServiceIntent, 0);
        NotificationCompat.Action prevAction = new NotificationCompat.Action(R.drawable.stop_icon, "Stop", pendingPrevIntent);

        NotificationCompat.Builder forenotificationBuilder = new NotificationCompat.Builder(HLSService.this, channel_id);
        Notification notification = forenotificationBuilder.setOngoing(true)
                .setSmallIcon(R.drawable.stream_icon)
                .setPriority(PRIORITY_MIN)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(prevAction)
                .build();


        startForeground(9999, notification);

        Log.d(TAG, "Service Started");

        notificationBuilder = new NotificationCompat.Builder(HLSService.this, channel_id);
        currentMillis = System.currentTimeMillis();
        notificationBuilder.setAutoCancel(true)
                .setOngoing(true)
                .setDefaults(Notification.DEFAULT_ALL)
                .setOnlyAlertOnce(true)
                .setWhen(currentMillis)
                .setSmallIcon(R.drawable.download_icon)
                .setTicker("Hearty365")
                .setContentTitle("Downloading")
                .setContentText("Downloading...")
                .setContentInfo("Info");

        FetchConfiguration fetchConfiguration = new FetchConfiguration.Builder(this)
                .enableLogging(true)
                .enableRetryOnNetworkGain(true)
                .setDownloadConcurrentLimit(8)
                .setHttpDownloader(new HttpUrlConnectionDownloader(Downloader.FileDownloaderType.PARALLEL))
                .build();

        fetch = Fetch.Impl.getInstance(fetchConfiguration);
        fetch.removeAll();

        notif_ids = new HashSet<>();
        retries = new HashMap<>();
        groups = new HashMap<>();
        group_files = new HashMap<>();
        groupstartTimes = new HashMap<>();

        fetchListener = new FetchListener() {
            @Override
            public void onAdded(@NotNull Download download) {
                Log.d(TAG, "Download Added " + download.getId());
            }

            @Override
            public void onQueued(@NotNull Download download, boolean waitOnNetwork) {
                Log.d(TAG, "Download Queued " + download.getId());
            }

            @Override
            public void onWaitingNetwork(@NotNull Download download) {
                Log.d(TAG, "Download Waiting " + download.getId());
            }

            @Override
            public void onCompleted(@NotNull Download download) {
                int download_id = download.getId();
                int group_id = download.getGroup();
                Log.d(TAG, "Download Completed " + download_id);
                fetch.getDownloadsInGroupWithStatus(group_id, Collections.singletonList(Status.COMPLETED), new Func<List<Download>>() {
                    @Override
                    public void call(@NotNull List<Download> result) {
                        if (result.size() == groups.get(group_id).keySet().size()) {
                            notificationBuilder.setContentText("Encoding")
                                    .setOngoing(true)
                                    .setSmallIcon(R.drawable.refresh_icon)
                                    .setProgress(0,0,true);
                            notificationManager.notify(group_id, notificationBuilder.build());
                            Log.d(NOTIF_TAG, "Encoding: " + group_id);

                            Map<String, Object> file_info = group_files.get(group_id);
                            String type = (String) file_info.get("type");

                            if (type.equals("mp4")) {
                                notificationBuilder.setContentText("Download Completed!")
                                        .setOngoing(false)
                                        .setSmallIcon(R.drawable.done_icon)
                                        .setProgress(0,0,false);
                                notificationManager.notify(group_id, notificationBuilder.build());
                                Log.d(NOTIF_TAG, "Completed MP4: " + group_id);
                                fetch.removeGroup(group_id);
                                return;
                            }

                            String[] ts_names = (String[]) file_info.get("ts_names");
                            String fname = (String) file_info.get("fname");

                            String ts_joined = String.join("|", ts_names);
                            String command = "-i 'concat:" + ts_joined + "' -c copy " + fname;
                            Log.d(TAG, "FFMPEG Command: " + command);
                            long executionId = FFmpeg.executeAsync(command, new ExecuteCallback() {

                                @Override
                                public void apply(final long executionId, final int returnCode) {
                                    if (returnCode == RETURN_CODE_SUCCESS) {
                                        Log.d(Config.TAG, "Async command execution completed successfully.");
                                        notificationBuilder.setContentText("Download Completed!")
                                                .setOngoing(false)
                                                .setSmallIcon(R.drawable.done_icon)
                                                .setProgress(0,0,false);
                                        notificationManager.notify(group_id, notificationBuilder.build());
                                        Log.d(NOTIF_TAG, "Completed M3U8: " + group_id);
                                        for (String ts : ts_names) {
                                            File f = new File(ts);
                                            boolean ifDeleted = f.delete();
                                            if (!ifDeleted) {
                                                Log.e(TAG, "Error Deleting File: " + ts);
                                            }
                                        }
                                        try {
                                            String fileName = new File(fname.replace(".mp4", "")).getName();
                                            File dirName = new File(Environment.getExternalStorageDirectory(), "/Download/HLS_Temp/" + fileName);
                                            if (dirName.exists()) {
                                                FileUtils.deleteDirectory(dirName);
                                            }
                                        } catch (IOException e) {
                                            Log.e(TAG, e.toString());
                                            e.printStackTrace();
                                        }
                                    } else if (returnCode == RETURN_CODE_CANCEL) {
                                        Log.d(Config.TAG, "Async command execution cancelled by user.");
                                    } else {
                                        Log.d(Config.TAG, String.format("Async command execution failed with returnCode=%d.", returnCode));
                                    }
                                }
                            });
                            fetch.removeGroup(group_id);
                        }
                    }
                });
            }

            @Override
            public void onError(@NotNull Download download, @NotNull Error error, @org.jetbrains.annotations.Nullable Throwable throwable)
            {
                Log.e(TAG, "Error: " + error.toString());
                Integer download_id = download.getId();

                if(!(retries.containsKey(download_id))) {
                    retries.put(download_id, max_retries);
                }
                try {
                    int left_retries = retries.get(download_id);
                    Log.d(TAG, "Retries Left: " + left_retries);
                    if (left_retries > 0) {
                        left_retries -= 1;
                        retries.put(download_id, left_retries);
                        Log.d(TAG, "Retrying Download: " + download_id);
                        fetch.retry(download_id);
                    } else {
                        notificationBuilder.setContentText("Failed!")
                                .setSmallIcon(R.drawable.cancel_icon)
                                .setOngoing(false)
                                .setProgress(0,0,false);
                        notificationManager.notify(download.getGroup(), notificationBuilder.build());
                        Log.d(NOTIF_TAG, "Failed: " + download.getGroup());
                    }
                } catch (NullPointerException e) {
                    Log.e(TAG, e.getMessage());
                }
            }

            @Override
            public void onDownloadBlockUpdated(@NotNull Download download, @NotNull DownloadBlock downloadBlock, int i) {}

            @Override
            public void onStarted(@NotNull Download download, @NotNull List<? extends DownloadBlock> list, int i) {
                Log.d(TAG, "Started Download: " + download.getId());
            }

            @Override
            public void onProgress(@NotNull Download download, long etaInMilliSeconds, long downloadedBytesProgress) {
                int download_id = download.getId();
                int group_id = download.getGroup();
                Map<Integer, Map<String, Long>> group_map = groups.get(group_id);
                Map<String, Long> download_map = group_map.get(download_id);

                int progress = download.getProgress();

                download_map.put("progress", (long) progress);
                download_map.put("eta", etaInMilliSeconds);
                download_map.put("speed", downloadedBytesProgress);
                group_map.put(download_id, download_map);
                groups.put(group_id, group_map);

                h.post(groupProgressThread);
            }

            @Override
            public void onPaused(@NotNull Download download) {
                Log.d(TAG, "Download Paused " + download.getId());
            }

            @Override
            public void onResumed(@NotNull Download download) {
                Log.d(TAG, "Download Resumed " + download.getId());
            }

            @Override
            public void onCancelled(@NotNull Download download) {
                int download_id = download.getId();
                int group_id = download.getGroup();

                Log.d(TAG, "Download  " + download_id);
                notificationBuilder.setProgress(0, 0, false)
                        .setSmallIcon(R.drawable.cancel_icon)
                        .setOngoing(false)
                        .setContentText("Cancelled!");
                notificationManager.notify(group_id, notificationBuilder.build());
                Log.d(NOTIF_TAG, "Cancelled: " + group_id);
                notif_ids.remove(group_id);
                groups.remove(group_id);
            }

            @Override
            public void onRemoved(@NotNull Download download) {
                Log.d(TAG, "Download Removed " + download.getId());

            }

            @Override
            public void onDeleted(@NotNull Download download) {
                Log.d(TAG, "Download Deleted " + download.getId());

            }
        };

        fetch.addListener(fetchListener);

        groupProgressThread = new Runnable() {
            @Override
            public void run() {
                for (int group_id: groups.keySet()) {
                    Map<Integer, Map<String, Long>> group_map = groups.get(group_id);
                    long total_progress = 0, speed = 0, eta = 0, avg_eta = 0, avg_speed;
                    int numDownloads = 0;
                    for (int download_id: group_map.keySet()) {
                        Map<String, Long> download_map = group_map.get(download_id);
                        total_progress += download_map.get("progress");
                        long download_speed = download_map.get("speed");
                        if (download_speed > 0) {
                            speed += download_speed;
                            numDownloads++;
                        }
                    }
                    int totalDownloads = group_map.keySet().size();
                    if (total_progress > 0) {
                        eta = System.currentTimeMillis() - groupstartTimes.get(group_id);
                        avg_eta = ( eta / total_progress ) * 100 * totalDownloads - eta;
                    }
                    if (numDownloads > 0) avg_speed = speed / numDownloads;
                    else avg_speed = 0;

                    long eta_sec = Math.max(avg_eta / 1000, 0);
                    long scale = (long)(Math.log10(avg_speed) / 3);
                    Double scaled_speed = avg_speed / Math.pow(1000, scale);
                    df.setRoundingMode(RoundingMode.DOWN);
                    String formattedSpeed = df.format(scaled_speed);
                    if(scale == 0) formattedSpeed += " B/s";
                    else if (scale == 1) formattedSpeed += " KB/s";
                    else if (scale == 2) formattedSpeed += " MB/s";
                    else formattedSpeed += " GB/s";

                    notificationBuilder.setProgress(100 * totalDownloads, (int) total_progress, false)
                            .setSmallIcon(R.drawable.download_icon)
                            .setOngoing(true)
                            .setContentText("ETA: " + DateUtils.formatElapsedTime(eta_sec) + " Speed: " + formattedSpeed);
                    notificationManager.notify(group_id, notificationBuilder.build());
                }
            }
        };

        h.postDelayed(groupProgressThread, 1000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Start Command");
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        Log.d(TAG, "Action: " + action);
        Bundle bundle = intent.getExtras();
        File dir_downloads = Environment.getExternalStorageDirectory();
        String downloads = dir_downloads.getAbsolutePath() + "/Download";
        int download_id, action_id;
        if(action != null) {
            switch (action) {
                case ACTION_ENQUEUE:
                    if (bundle != null) {
                        String url, file, type;
                        url = bundle.getString("url");
                        file = bundle.getString("file");
                        type = bundle.getString("type");
                        String fileName = new File(file.replace(".mp4", "")).getName();
                        File dirName = new File(Environment.getExternalStorageDirectory(), "Download/HLS_Temp/" + fileName);
                        if(!dirName.exists()) {
                            Log.d(TAG, "Creating Dir: " + dirName.getAbsolutePath());
                            dirName.mkdirs();
                        }
                        int group_id = url.hashCode();
                        if (type.equals("m3u8")) {
                            ArrayList<String> ts_files = bundle.getStringArrayList("ts_files");
                            ts_names = new String[ts_files.size()];
                            int i = 0;
                            Map<Integer, Map<String, Long>> group_map = new LinkedHashMap<>();
                            for (String ts : ts_files.toArray(new String[ts_files.size()])) {
                                i += 1;
                                String fname = dirName.getAbsolutePath() + "/" + i + ".ts";
                                download_id = enqueueDownload(ts, fname, group_id);
                                Map<String, Long> download_map = new LinkedHashMap<>();
                                download_map.put("progress", (long) -1);
                                download_map.put("eta", (long) -1);
                                download_map.put("speed", (long) -1);
                                group_map.put(download_id, download_map);
                                ts_names[i - 1] = fname;
                            }
                            groups.put(group_id, group_map);
                            groupstartTimes.put(group_id, System.currentTimeMillis());

                            Map<String, Object> file_info = new LinkedHashMap<>();
                            file_info.put("ts_names", ts_names);
                            file_info.put("fname", file.trim());
                            file_info.put("type", type);
                            group_files.put(group_id, file_info);
                        } else if (type.equals("yt")) {
                            executor.execute(new Runnable() {
                                @SuppressLint("RestrictedApi")
                                @Override
                                public void run() {
                                    try {
                                        YoutubeDLRequest request = new YoutubeDLRequest(url);
                                        String format_id = bundle.getString("format_id");
                                        if (format_id.equals("bestaudio")) {
                                            request.addOption("-f", format_id);
                                            request.addOption("-x");
                                            request.addOption("--audio-format", "mp3");
                                            request.addOption("--embed-thumbnail");
                                            request.addOption("--add-metadata");
                                        }
                                        else request.addOption("-f", format_id + "+bestaudio");
                                        request.addOption("-o", downloads + "/" + file + "-" + format_id + ".%(ext)s");
                                        notificationBuilder.mActions.clear();
                                        notificationBuilder.setProgress(0, 0, true)
                                                            .setSmallIcon(R.drawable.download_icon)
                                                            .setOngoing(true);
                                        notificationManager.notify(group_id, notificationBuilder.build());
                                        Log.d(NOTIF_TAG, "Adding YT: " + group_id);
                                        YoutubeDLResponse youtubeDLResponse = YoutubeDL.getInstance().execute(request, (progress, etaInSeconds) -> {
                                            notificationBuilder.setProgress(100 , (int) progress, false)
                                                    .setSmallIcon(R.drawable.download_icon)
                                                    .setOngoing(true)
                                                    .setContentText("ETA: " + DateUtils.formatElapsedTime(etaInSeconds) );
                                            notificationManager.notify(group_id, notificationBuilder.build());
                                            Log.d(NOTIF_TAG, "Added YT: " + group_id);
                                            if((int)progress == 100) {
                                                notificationBuilder.setProgress(0, 0, true)
                                                        .setContentText("Encoding!")
                                                        .setSmallIcon(R.drawable.refresh_icon)
                                                        .setOngoing(true);
                                                notificationManager.notify(group_id, notificationBuilder.build());
                                                Log.d(NOTIF_TAG, "Encoding YT: " + group_id);
                                            }
                                        });
                                        String out = youtubeDLResponse.getOut();
                                        String err = youtubeDLResponse.getErr();
                                        if (out != null && out.length() > 0) Log.d(TAG, "YoutubeDL Output: " + out);
                                        if (err != null && err.length() > 0) Log.e(TAG, "YoutubeDL Error: " + err);
                                        Thread.sleep(1000);
                                        notificationBuilder.setContentText("Download Completed!")
                                                .setOngoing(false)
                                                .setSmallIcon(R.drawable.done_icon)
                                                .setProgress(0,0,false);
                                        notificationManager.notify(group_id, notificationBuilder.build());
                                        Log.d(NOTIF_TAG, "Completed YT: " + group_id);
                                    } catch (Exception e) {
                                        Log.e(TAG, "failed to initialize youtubedl-android", e);
                                        e.printStackTrace();
                                    }
                                }
                            });
                        } else {
                            Map<Integer, Map<String, Long>> group_map = new LinkedHashMap<>();
                            Map<String, Object> file_info = new LinkedHashMap<>();

                            file_info.put("ts_names", null);
                            file_info.put("fname", file.trim());
                            file_info.put("type", type);
                            group_files.put(group_id, file_info);

                            download_id = enqueueDownload(url, file, group_id);

                            Map<String, Long> download_map = new LinkedHashMap<>();
                            download_map.put("progress", (long) -1);
                            download_map.put("eta", (long) -1);
                            download_map.put("speed", (long) -1);
                            group_map.put(download_id, download_map);

                            groups.put(group_id, group_map);
                            groupstartTimes.put(group_id, System.currentTimeMillis());
                        }
                    }
                    break;
                case ACTION_PAUSE:
                    if (bundle != null) {
                        action_id = bundle.getInt("group_id");
                        pauseDownload(action_id);
                    }
                    break;
                case ACTION_RESUME:
                    if (bundle != null) {
                        action_id = bundle.getInt("group_id");
                        resumeDownload(action_id);
                    }
                    break;
                case ACTION_CANCEL:
                    if (bundle != null) {
                        action_id = bundle.getInt("group_id");
                        fetch.cancelGroup(action_id);
                    }
                    break;
                case ACTION_START_SERVICE:
                    Log.d(TAG, "Service Started!");
                    break;
                case ACTION_STOP_SERVICE:
                    Log.d(TAG, "Stopping Service");
                    Exiter.exitApplication(HLSService.this);
                    stopForeground(true);
                    stopSelf();
                    break;
                default:
                    break;
            }
        }

        fetch.getDownloads(new Func<List<Download>>() {
            @Override
            public void call(List<Download> downloads) {
                for(Download download: downloads) {
                    Log.d(TAG, "Download ID: " + download.getId() + " Status: " + download.getStatus() + " Progress: " + download.getProgress() + " Error: " + download.getError() + " URL: " + download.getUrl());
                }
            }
        });

        return START_STICKY;
    }

    public NotificationCompat.Action getPauseAction(int group_id) {
        Intent pauseServiceIntent = new Intent(this, HLSService.class);
        pauseServiceIntent.setAction(ACTION_PAUSE);
        pauseServiceIntent.putExtra("group_id", group_id);
        PendingIntent pendingPauseIntent = PendingIntent.getService(this, 0, pauseServiceIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Action(R.drawable.pause_icon, "Pause", pendingPauseIntent);
    }

    public NotificationCompat.Action getResumeAction(int group_id) {
        Intent resumeServiceIntent = new Intent(this, HLSService.class);
        resumeServiceIntent.setAction(ACTION_RESUME);
        resumeServiceIntent.putExtra("group_id", group_id);
        PendingIntent pendingResumeIntent = PendingIntent.getService(this, 0, resumeServiceIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Action(R.drawable.resume_icon, "Resume", pendingResumeIntent);
    }

    public NotificationCompat.Action getCancelAction(int group_id) {
        Intent cancelServiceIntent = new Intent(this, HLSService.class);
        cancelServiceIntent.setAction(ACTION_CANCEL);
        cancelServiceIntent.putExtra("group_id", group_id);
        PendingIntent pendingCancelIntent = PendingIntent.getService(this, 0, cancelServiceIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Action(R.drawable.pause_icon, "Cancel", pendingCancelIntent);
    }

    @SuppressLint("RestrictedApi")
    public int enqueueDownload(String url, String file, int group_id) {
        Log.d(TAG, "URL: " + url);
        Log.d(TAG, "File: " + file);
        final Request request = new Request(url, file);
        request.setPriority(Priority.HIGH);
        request.setNetworkType(NetworkType.ALL);
        request.setGroupId(group_id);

        final int download_id = request.getId();

        fetch.enqueue(request, updatedRequest -> {

            Log.d(TAG, "Requested Enqueued: " + request.getUrl());
            Log.d(TAG, "Request ID: " + download_id);
            notif_ids.add(group_id);

            notificationBuilder.mActions.clear();
            notificationBuilder.setProgress(0, 0, true)
                                .setSmallIcon(R.drawable.download_icon)
                                .addAction(getPauseAction(group_id))
                                .addAction(getCancelAction(group_id));
            notificationManager.notify(group_id, notificationBuilder.build());
            Log.d(NOTIF_TAG, "Enqueued: " + group_id);

        }, error -> {

            Log.e(TAG, error.toString());
        });
        return download_id;
    }

    @SuppressLint("RestrictedApi")
    public void pauseDownload(int group_id) {
        fetch.pauseGroup(group_id);

        notificationBuilder.mActions.clear();
        notificationBuilder.setContentText("Paused!")
                .addAction(getResumeAction(group_id))
                .addAction(getCancelAction(group_id));
        notificationManager.notify(group_id, notificationBuilder.build());
        Log.d(NOTIF_TAG, "Paused: " + group_id);
    }

    @SuppressLint("RestrictedApi")
    public void resumeDownload(int group_id) {
        fetch.resumeGroup(group_id);

        notificationBuilder.mActions.clear();
        notificationBuilder.setContentText("Resuming...")
                .addAction(getPauseAction(group_id))
                .addAction(getCancelAction(group_id));
        notificationManager.notify(group_id, notificationBuilder.build());
        Log.d(NOTIF_TAG, "Resumed: " + group_id);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        fetch.removeAllWithStatus(Status.COMPLETED);
        fetch.close();

        try {
            File tempDir = new File(Environment.getExternalStorageDirectory(), "/Download/HLS_Temp");
            FileUtils.deleteDirectory(tempDir);
        } catch(IOException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
        }


        Log.d(TAG, "Fetched Closed!");
        notificationManager.cancelAll();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

}

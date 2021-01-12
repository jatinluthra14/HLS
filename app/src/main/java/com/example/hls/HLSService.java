package com.example.hls;

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
import android.os.IBinder;
import android.text.format.DateUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.tonyodev.fetch2.Download;
import com.tonyodev.fetch2.Error;
import com.tonyodev.fetch2.Fetch;
import com.tonyodev.fetch2.FetchConfiguration;
import com.tonyodev.fetch2.FetchListener;
import com.tonyodev.fetch2.HttpUrlConnectionDownloader;
import com.tonyodev.fetch2.NetworkType;
import com.tonyodev.fetch2.Priority;
import com.tonyodev.fetch2.Request;
import com.tonyodev.fetch2core.DownloadBlock;
import com.tonyodev.fetch2core.Downloader;
import com.tonyodev.fetch2core.Func;

import org.jetbrains.annotations.NotNull;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static androidx.core.app.NotificationCompat.PRIORITY_MIN;

public class HLSService extends Service {

    public static final String ACTION_START_SERVICE = "ACTION_START_SERVICE";
    public static final String ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE";
    public static final String ACTION_ENQUEUE = "ACTION_ENQUEUE";
    public static final String ACTION_RESUME = "ACTION_RESUME";
    public static final String ACTION_PAUSE = "ACTION_PAUSE";
    public static final String ACTION_CANCEL = "ACTION_CANCEL";

    String TAG = "HLSService", channel_id = "Download", url = "", file = "";
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

    @Override
    public void onCreate() {
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        Log.d(TAG, "Build Greater");
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
                .setWhen(currentMillis)
                .setSmallIcon(R.drawable.download_icon)
                .setTicker("Hearty365")
                .setContentTitle("Downloading")
                .setContentText("Downloading...")
                .setContentInfo("Info");

        FetchConfiguration fetchConfiguration = new FetchConfiguration.Builder(this)
                .enableRetryOnNetworkGain(true)
                .setDownloadConcurrentLimit(8)
                .setHttpDownloader(new HttpUrlConnectionDownloader(Downloader.FileDownloaderType.PARALLEL))
                .build();

        fetch = Fetch.Impl.getInstance(fetchConfiguration);

        notif_ids = new HashSet<>();
        retries = new HashMap<>();

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
                Log.d(TAG, "Download Completed " + download_id);
                notificationBuilder.setContentText("Downloading Completed")
                        .setOngoing(false)
                        .setSmallIcon(R.drawable.done_icon)
                        .setProgress(0,0,false);
                notificationManager.notify(download_id, notificationBuilder.build());
                fetch.remove(download_id);
            }

            @Override
            public void onError(@NotNull Download download, @NotNull Error error, @org.jetbrains.annotations.Nullable Throwable throwable)
            {
                Log.e(TAG, "Error: " + error.toString());
                Integer download_id = download.getId();
                notificationBuilder.setContentText("Stopped!")
                        .setSmallIcon(R.drawable.cancel_icon)
                        .setOngoing(false)
                        .setProgress(0,0,false);
                notificationManager.notify(download_id, notificationBuilder.build());

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
                        notificationManager.notify(download_id, notificationBuilder.build());
                    }
                } catch (NullPointerException e) {
                    Log.e(TAG, e.getMessage());
                }
            }

            @Override
            public void onDownloadBlockUpdated(@NotNull Download download, @NotNull DownloadBlock downloadBlock, int i) {

            }

            @Override
            public void onStarted(@NotNull Download download, @NotNull List<? extends DownloadBlock> list, int i)
            {
                Log.d(TAG, "Started Download: " + download.getId());
            }

            @Override
            public void onProgress(@NotNull Download download, long etaInMilliSeconds, long downloadedBytesProgress) {
                long eta = etaInMilliSeconds / 1000;
                int progress = download.getProgress();
                long scale = (long)(Math.log10(downloadedBytesProgress) / 3);
                Double scaled_speed = downloadedBytesProgress / Math.pow(1000, scale);
                df.setRoundingMode(RoundingMode.DOWN);
                String speed = df.format(scaled_speed);
                if(scale == 0) {
                    speed += " B/s";
                } else if (scale == 1) {
                    speed += " KB/s";
                } else if (scale == 2) {
                    speed += " MB/s";
                } else {
                    speed += " GB/s";
                }
//                Log.d(TAG, "ETA: " + DateUtils.formatElapsedTime(eta) + " Downloaded Bytes: " + downloadedBytesProgress / 1024 + " Progress: " + progress);
                notificationBuilder.setProgress(100, progress, false)
                        .setSmallIcon(R.drawable.download_icon)
                        .setOngoing(true)
                        .setContentText("ETA: " + DateUtils.formatElapsedTime(eta) + " Speed: " + speed);
                notificationManager.notify(download.getId(), notificationBuilder.build());

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
                Log.d(TAG, "Download Cancelled " + download_id);
                notificationBuilder.setProgress(0, 0, false)
                        .setSmallIcon(R.drawable.cancel_icon)
                        .setOngoing(false)
                        .setContentText("Cancelled!");
                notificationManager.notify(download_id, notificationBuilder.build());
                notif_ids.remove(download_id);
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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Start Command");
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        Log.d(TAG, "Action: " + action);
        Bundle bundle = intent.getExtras();
        int download_id;
        if(action != null) {
            switch (action) {
                case ACTION_ENQUEUE:
                    if (bundle != null) {
                        url = bundle.getString("url");
                        file = bundle.getString("file");
                        enqueueDownload(url, file);
                    }
                    break;
                case ACTION_PAUSE:
                    if (bundle != null) {
                        download_id = bundle.getInt("download_id");
                        pauseDownload(download_id);
                    }
                    break;
                case ACTION_RESUME:
                    if (bundle != null) {
                        download_id = bundle.getInt("download_id");
                        resumeDownload(download_id);
                    }
                    break;
                case ACTION_CANCEL:
                    if (bundle != null) {
                        download_id = bundle.getInt("download_id");
                        fetch.cancel(download_id);
                    }
                    break;
                case ACTION_START_SERVICE:
                    Log.d(TAG, "Service Started!");
                    break;
                case ACTION_STOP_SERVICE:
                    Log.d(TAG, "Stopping Service");
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
                //Access all downloads here
                for(Download download: downloads) {
                    Log.d(TAG, "Download ID: " + download.getId() + " Status: " + download.getStatus() + " Progress: " + download.getProgress() + " Error: " + download.getError() + " URL: " + download.getUrl());
                }
            }
        });

        return START_STICKY;
    }

    public NotificationCompat.Action getPauseAction(int download_id) {
        Intent pauseServiceIntent = new Intent(this, HLSService.class);
        pauseServiceIntent.setAction(ACTION_PAUSE);
        pauseServiceIntent.putExtra("download_id", download_id);
        PendingIntent pendingPauseIntent = PendingIntent.getService(this, 0, pauseServiceIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Action(R.drawable.pause_icon, "Pause", pendingPauseIntent);
    }

    public NotificationCompat.Action getResumeAction(int download_id) {
        Intent resumeServiceIntent = new Intent(this, HLSService.class);
        resumeServiceIntent.setAction(ACTION_RESUME);
        resumeServiceIntent.putExtra("download_id", download_id);
        PendingIntent pendingResumeIntent = PendingIntent.getService(this, 0, resumeServiceIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Action(R.drawable.resume_icon, "Resume", pendingResumeIntent);
    }

    public NotificationCompat.Action getCancelAction(int download_id) {
        Intent cancelServiceIntent = new Intent(this, HLSService.class);
        cancelServiceIntent.setAction(ACTION_CANCEL);
        cancelServiceIntent.putExtra("download_id", download_id);
        PendingIntent pendingCancelIntent = PendingIntent.getService(this, 0, cancelServiceIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Action(R.drawable.pause_icon, "Cancel", pendingCancelIntent);
    }

    public void enqueueDownload(String url, String file) {
        Log.d(TAG, "URL: " + url);
        Log.d(TAG, "File: " + file);
        final Request request = new Request(url, file);
        request.setPriority(Priority.HIGH);
        request.setNetworkType(NetworkType.ALL);
//        request.addHeader("clientKey", "SD78DF93_3947&MVNGHE1WONG");

        fetch.enqueue(request, updatedRequest -> {
            //Request was successfully enqueued for download.
            Log.d(TAG, "Requested Enqueued: " + request.getUrl());
            int download_id = request.getId();
            Log.d(TAG, "Request ID: " + download_id);
            notif_ids.add(download_id);

            notificationBuilder.setProgress(100, 0, false)
                                .addAction(getPauseAction(download_id))
                                .addAction(getCancelAction(download_id));
            notificationManager.notify(download_id, notificationBuilder.build());
        }, error -> {
            //An error occurred enqueuing the request.
            Log.e(TAG, error.toString());
        });
    }

    @SuppressLint("RestrictedApi")
    public void pauseDownload(int download_id) {
        fetch.pause(download_id);

        notificationBuilder.mActions.clear();
        notificationBuilder.setContentText("Paused!")
                .addAction(getResumeAction(download_id))
                .addAction(getCancelAction(download_id));
        notificationManager.notify(download_id, notificationBuilder.build());
    }

    @SuppressLint("RestrictedApi")
    public void resumeDownload(int download_id) {
        fetch.resume(download_id);

        notificationBuilder.mActions.clear();
        notificationBuilder.setContentText("Resuming...")
                .addAction(getPauseAction(download_id))
                .addAction(getCancelAction(download_id));
        notificationManager.notify(download_id, notificationBuilder.build());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        fetch.cancelAll();
        fetch.deleteAll();
        fetch.close();
        for (Integer notif_id: notif_ids) {
            notificationBuilder.setContentText("Downloading Stopped!")
                    .setSmallIcon(R.drawable.cancel_icon)
                    .setOngoing(false)
                    .setProgress(0,0,false);
            notificationManager.notify(notif_id, notificationBuilder.build());
        }
        Log.d(TAG, "Fetched Closed!");
//        fetch.removeListener(fetchListener);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

}

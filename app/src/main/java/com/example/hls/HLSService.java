package com.example.hls;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.text.format.DateUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.arthenica.mobileffmpeg.Config;
import com.arthenica.mobileffmpeg.ExecuteCallback;
import com.arthenica.mobileffmpeg.FFmpeg;
import com.arthenica.mobileffmpeg.LogCallback;
import com.arthenica.mobileffmpeg.LogMessage;
import com.arthenica.mobileffmpeg.Statistics;
import com.arthenica.mobileffmpeg.StatisticsCallback;
import com.tonyodev.fetch2.Download;
import com.tonyodev.fetch2.Fetch;
import com.tonyodev.fetch2.FetchConfiguration;
import com.tonyodev.fetch2.FetchListener;
import com.tonyodev.fetch2.HttpUrlConnectionDownloader;
import com.tonyodev.fetch2.NetworkType;
import com.tonyodev.fetch2.Priority;
import com.tonyodev.fetch2.Request;
import com.tonyodev.fetch2core.DownloadBlock;
import com.tonyodev.fetch2core.Downloader;
import com.tonyodev.fetch2.Error;
import com.tonyodev.fetch2core.Func;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static androidx.core.app.NotificationCompat.PRIORITY_MIN;
import static com.arthenica.mobileffmpeg.Config.RETURN_CODE_CANCEL;
import static com.arthenica.mobileffmpeg.Config.RETURN_CODE_SUCCESS;

public class HLSService extends Service {

    public static final String ACTION_START_SERVICE = "ACTION_START_SERVICE";
    public static final String ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE";
    public static final String ACTION_ENQUEUE = "ACTION_ENQUEUE";
    public static final String ACTION_RESUME = "ACTION_RESUME";
    public static final String ACTION_PAUSE = "ACTION_PAUSE";
    public static final String ACTION_STOP = "ACTION_STOP";

    String TAG = "HLSService", ffmpeg_command = "", channel_id = "Download", url = "", file = "";
    NotificationManager notificationManager;
    NotificationCompat.Builder notificationBuilder;
    int dur_flag = 0, final_duration=0, notif_id=0;
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d(TAG, "Build Greater");
            NotificationChannel notificationChannel = new NotificationChannel(channel_id, "HLS Service", NotificationManager.IMPORTANCE_HIGH);

            // Configure the notification channel.
            notificationChannel.setDescription("HLS Download Progress");
            notificationChannel.enableLights(true);
            notificationChannel.setLightColor(Color.RED);
            notificationChannel.setVibrationPattern(new long[]{0, 1000, 500, 1000});
            notificationChannel.enableVibration(true);
            notificationManager.createNotificationChannel(notificationChannel);
        }
        NotificationCompat.Builder forenotificationBuilder = new NotificationCompat.Builder(HLSService.this, channel_id);
        Notification notification = forenotificationBuilder.setOngoing(true)
                .setSmallIcon(R.drawable.stream_icon)
                .setPriority(PRIORITY_MIN)
                .setCategory(Notification.CATEGORY_SERVICE)
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

        notif_ids = new HashSet<Integer>();
        retries = new HashMap<Integer, Integer>();

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
                notificationBuilder.setContentText("Downloading Stopped!")
                        .setSmallIcon(R.drawable.cancel_icon)
                        .setOngoing(false)
                        .setProgress(0,0,false);
                notificationManager.notify((int) download_id, notificationBuilder.build());

                if(!(retries.containsKey(download_id))) {
                    retries.put(download_id, max_retries);
                }
                int left_retries = retries.get(download_id);
                Log.d(TAG, "Retries Left: " + left_retries);
                if (left_retries > 0) {
                    left_retries -= 1;
                    retries.put(download_id, left_retries);
                    Log.d(TAG, "Retrying Download: " + download_id);
                    fetch.retry(download_id);
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
                Long eta = etaInMilliSeconds / 1000;
                int progress = download.getProgress();
                long scale = (long)(Math.log10(downloadedBytesProgress) / 3);
                Double scaled_speed = downloadedBytesProgress / Math.pow(1000, scale);
                df.setRoundingMode(RoundingMode.DOWN);
                String speed = df.format(scaled_speed).toString();
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
                notificationBuilder.setContentText("Downloading Stopped!")
                        .setSmallIcon(R.drawable.download_icon)
                        .setOngoing(true)
                        .setProgress(100,download.getProgress(),false);
                notificationManager.notify((int) download.getId(), notificationBuilder.build());
            }

            @Override
            public void onCancelled(@NotNull Download download) {
                Log.d(TAG, "Download Cancelled " + download.getId());
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
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if(action != null) {
            switch (action) {
                case ACTION_ENQUEUE:
                    Bundle bundle = intent.getExtras();
                    if (bundle != null) {
                        url = bundle.getString("url");
                        file = bundle.getString("file");
                    }
                    enqueueDownload(url, file);
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

//        fetch.cancelAll();
//        fetch.deleteAll();

//        long executionId = FFmpeg.executeAsync(ffmpeg_command, new ExecuteCallback() {
//
//            @Override
//            public void apply(final long executionId, final int returnCode) {
//                if (returnCode == RETURN_CODE_SUCCESS) {
//                    Log.d(Config.TAG, "Async command execution completed successfully.");
//                } else if (returnCode == RETURN_CODE_CANCEL) {
//                    Log.d(Config.TAG, "Async command execution cancelled by user.");
//                } else {
//                    Log.d(Config.TAG, String.format("Async command execution failed with returnCode=%d.", returnCode));
//                }
//            }
//        });
//
//        Log.d(TAG, "Async Command Started in Service!");
//
//        Config.enableStatisticsCallback(new StatisticsCallback() {
//            public void apply(Statistics newStatistics) {
////                Log.d(Config.TAG, String.format("frame: %d, time: %d", newStatistics.getVideoFrameNumber(), newStatistics.getTime()));
//                int timeInMilliseconds = newStatistics.getTime();
//                if (timeInMilliseconds > 0) {
//                    int totalVideoDuration = final_duration;
////                    Log.i(TAG, "Final Video Duration: " + final_duration);
//
//                    BigDecimal completePercentage = new BigDecimal(timeInMilliseconds).multiply(new BigDecimal(100)).divide(new BigDecimal(totalVideoDuration), 0, BigDecimal.ROUND_HALF_UP);
//                    long nowMillis = System.currentTimeMillis();
//                    long diffMillis = nowMillis - currentMillis;
//                    Log.d(TAG, "Time Passed: " + diffMillis + "   " + DateUtils.formatElapsedTime(diffMillis / 1000));
//                    Double etrMillis = ((diffMillis / completePercentage.doubleValue()) * 100 - diffMillis) / 1000;
//                    String etr = DateUtils.formatElapsedTime(Math.round(etrMillis));
//                    notificationBuilder.setProgress(100, completePercentage.intValue(), false)
//                                        .setContentText(completePercentage.toString() + "% ETR: " + etr);
//                    notificationManager.notify((int) notif_id, notificationBuilder.build());
//
//                    if (completePercentage.intValue() >= 100) {
//                        notificationBuilder.setContentText("Encoding completed")
//                                // Removes the progress bar
//                                .setProgress(0,0,false);
//                        notificationManager.notify((int) notif_id, notificationBuilder.build());
//                    }
//                }
//            }
//        });
//
//        Config.enableLogCallback(new LogCallback() {
//            public void apply(LogMessage message) {
//                if (dur_flag == 1) {
//                    duration = message.getText();
//                    dur_flag = 0;
//                }
//                Log.d(Config.TAG, message.getText());
//                if (message.getText().contains("Duration:")) {
//                    Log.d(TAG, "Got Duration!");
//                    dur_flag = 1;
//                }
//            }
//        });
//
//        while (duration == "") {
////            try {
////                Log.i(TAG, "Waiting for Duration");
////                Thread.sleep(1000);
////            } catch (InterruptedException e) {
////                Log.d("TAG", "sleep failure");
////
////            }
//            continue;
//        }
//        Log.d(TAG, "Duration Found: " + duration);
//
//        String[] units = duration.split(":"); //will break the string up into an array
//        int hours = Integer.parseInt(units[0]);
//        int minutes = Integer.parseInt(units[1]); //first element
//        String[] second = units[2].split("\\."); //second element
//        int seconds = Integer.parseInt(second[0]);
//        int milli = Integer.parseInt(second[1]);
//        final_duration = 1000 * (60 * (60 * hours + minutes) + seconds) + milli;
//
//        Log.d(TAG, "Duration in Milli: " + final_duration);
//

        return START_STICKY;
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
            notif_id = request.getId();
            notif_ids.add(notif_id);

            notificationBuilder.setProgress(100, 0, false);
            notificationManager.notify(notif_id, notificationBuilder.build());
        }, error -> {
            //An error occurred enqueuing the request.
            Log.e(TAG, error.toString());
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        fetch.close();
        for (Integer notif_id: notif_ids) {
            notificationBuilder.setContentText("Downloading Stopped!")
                    .setSmallIcon(R.drawable.cancel_icon)
                    .setOngoing(false)
                    .setProgress(0,0,false);
            notificationManager.notify((int) notif_id, notificationBuilder.build());
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

package com.jtb.hls;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatSeekBar;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.util.Util;

import java.io.IOException;

public class Exoplayer extends AppCompatActivity {

    private PlayerView playerView;
    private SimpleExoPlayer player;
    private boolean playWhenReady = true;
    private int currentWindow = 0;
    private long playbackPosition = 0;
        private String path = "https://content.jwplatform.com/manifests/yp34SRmf.m3u8";

    private PlaybackStateListener playbackStateListener;
    private AppCompatSeekBar playback_speed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_exoplayer);
//        Toolbar toolbar = findViewById(R.id.toolbar);
//        setSupportActionBar(toolbar);
        Bundle bundle = getIntent().getExtras();
        if (bundle != null) {
            path = bundle.getString("path");
        }
        playbackStateListener = new PlaybackStateListener();
        playerView = findViewById(R.id.video_view);
        playback_speed = findViewById(R.id.playback_speed);
        playback_speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float speed = 0.25f*progress + 1;
                PlaybackParameters param = new PlaybackParameters(speed);
                player.setPlaybackParameters(param);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        Log.v("Util", "----------------------------------");
        Log.i("Util", String.valueOf(Util.SDK_INT));
        initializePlayer();
    }

    @Override
    public void onResume() {
        super.onResume();
        hideSystemUi();
        if (player == null) {
            initializePlayer();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onStop() {
        super.onStop();
        releasePlayer();
    }

    private void initializePlayer() {
        Log.i("Util", "Initializing Player");
        player = new SimpleExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        MediaItem mediaItem = MediaItem.fromUri(path);
        player.setMediaItem(mediaItem);
        Log.i("Util", String.valueOf(playWhenReady));
        player.setPlayWhenReady(playWhenReady);
        player.seekTo(currentWindow, playbackPosition);
        player.addListener(playbackStateListener);
        player.setWakeMode(C.WAKE_MODE_LOCAL);
        player.prepare();
    }

    private void releasePlayer() {
        if (player != null) {
            playWhenReady = player.getPlayWhenReady();
            playbackPosition = player.getCurrentPosition();
            currentWindow = player.getCurrentWindowIndex();
            player.removeListener(playbackStateListener);
            player.release();
            player = null;
        }
    }

    @SuppressLint("InlinedApi")
    private void hideSystemUi() {
        playerView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    }

    private class PlaybackStateListener implements Player.EventListener {
        @Override
        public void onPlaybackStateChanged(int playbackState) {
            String stateString;
            switch (playbackState) {
                case ExoPlayer.STATE_IDLE:
                    stateString = "ExoPlayer.STATE_IDLE      -";
                    break;
                case ExoPlayer.STATE_BUFFERING:
                    stateString = "ExoPlayer.STATE_BUFFERING -";
                    break;
                case ExoPlayer.STATE_READY:
                    stateString = "ExoPlayer.STATE_READY     -";
                    break;
                case ExoPlayer.STATE_ENDED:
                    stateString = "ExoPlayer.STATE_ENDED     -";
                    break;
                default:
                    stateString = "UNKNOWN_STATE             -";
                    break;
            }
            Log.i("Status", "changed state to " + stateString);
            if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED ||
                    !playWhenReady) {
                playerView.setKeepScreenOn(false);
            } else {
                // This prevents the screen from getting dim/lock
                playerView.setKeepScreenOn(true);
            }
        }

        @Override
        public void onPlayerError(ExoPlaybackException error) {
            if (error.type == ExoPlaybackException.TYPE_SOURCE) {
                IOException cause = error.getSourceException();
                Toast.makeText(Exoplayer.this, String.valueOf(cause), Toast.LENGTH_LONG).show();

            }
        }
    }

////    @Override
////    public void onConfigurationChanged(Configuration newConfig) {
////        super.onConfigurationChanged(newConfig);
////        Log.i("Status", "Orientation Changed");
////
////        // Checking the orientation of the screen
////        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
////            //First Hide other objects (listview or recyclerview), better hide them using Gone.
////            CoordinatorLayout.LayoutParams params = (CoordinatorLayout.LayoutParams) playerView.getLayoutParams();
////            params.width=params.MATCH_PARENT;
////            params.height=params.MATCH_PARENT;
////            playerView.setLayoutParams(params);
////        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT){
////            //unhide your objects here.
////            CoordinatorLayout.LayoutParams params = (CoordinatorLayout.LayoutParams) playerView.getLayoutParams();
////            params.width=params.MATCH_PARENT;
////            params.height=600;
////            playerView.setLayoutParams(params);
////        }
//    }
}

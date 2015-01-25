package com.kabouzeid.materialmusic.service;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.RemoteControlClient;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import com.kabouzeid.materialmusic.R;
import com.kabouzeid.materialmusic.helper.NotificationHelper;
import com.kabouzeid.materialmusic.helper.Shuffler;
import com.kabouzeid.materialmusic.interfaces.OnMusicRemoteEventListener;
import com.kabouzeid.materialmusic.misc.AppKeys;
import com.kabouzeid.materialmusic.model.MusicRemoteEvent;
import com.kabouzeid.materialmusic.model.Song;
import com.kabouzeid.materialmusic.util.InternalStorageUtil;
import com.kabouzeid.materialmusic.util.MusicUtil;
import com.nostra13.universalimageloader.core.ImageLoader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class MusicService extends Service implements MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener, MediaPlayer.OnCompletionListener, AudioManager.OnAudioFocusChangeListener {
    private static final String TAG = MusicService.class.getSimpleName();

    public static final String ACTION_TOGGLE_PLAYBACK = "com.kabouzeid.materialmusic.action.TOGGLE_PLAYBACK";
    public static final String ACTION_PLAY = "com.kabouzeid.materialmusic.action.PLAY";
    public static final String ACTION_PAUSE = "com.kabouzeid.materialmusic.action.PAUSE";
    public static final String ACTION_STOP = "com.kabouzeid.materialmusic.action.STOP";
    public static final String ACTION_SKIP = "com.kabouzeid.materialmusic.action.SKIP";
    public static final String ACTION_REWIND = "com.kabouzeid.materialmusic.action.REWIND";
    public static final String ACTION_QUIT = "com.kabouzeid.materialmusic.action.QUIT";

    public static final int SHUFFLE_MODE_NONE = 0;
    public static final int SHUFFLE_MODE_SHUFFLE = 1;

    public static final int REPEAT_MODE_NONE = 0;
    public static final int REPEAT_MODE_ALL = 1;
    public static final int REPEAT_MODE_THIS = 2;

    private MediaPlayer player;
    private List<Song> playingQueue;
    private LinkedList<Song> playingHistory;
    private List<OnMusicRemoteEventListener> onMusicRemoteEventListeners;
    private int currentSongId = -1;
    private int position = -1;
    private int shuffleMode;
    private int repeatMode;
    private final IBinder musicBind = new MusicBinder();
    private boolean isPlayerPrepared;
    private boolean wasPlayingBeforeFocusLoss;
    private boolean thingsRegistered;
    private NotificationHelper notificationHelper;
    private AudioManager audioManager;
    private RemoteControlClient remoteControlClient;
    private Shuffler shuffler;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) {
                pausePlaying();
            }
        }
    };

    private AudioManager getAudioManager() {
        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        return audioManager;
    }

    public MusicService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        isPlayerPrepared = false;
        playingQueue = new ArrayList<>();
        playingHistory = new LinkedList<>();
        onMusicRemoteEventListeners = new ArrayList<>();
        notificationHelper = new NotificationHelper(this);

        shuffleMode = PreferenceManager.getDefaultSharedPreferences(this).getInt(AppKeys.SP_SHUFFLE_MODE, 0);
        repeatMode = PreferenceManager.getDefaultSharedPreferences(this).getInt(AppKeys.SP_REPEAT_MODE, 0);

        registerEverything();
    }

    private Shuffler getShuffler() {
        if (shuffler == null) {
            shuffler = new Shuffler(playingQueue.size());
        }
        return shuffler;
    }

    private boolean requestFocus() {
        int result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);

        return (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    }

    private void initRemoteControlClient() {
        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.setComponent(new ComponentName(getApplicationContext(), MediaButtonIntentReceiver.class));
        PendingIntent mediaPendingIntent = PendingIntent.getBroadcast(getApplicationContext(), 0, mediaButtonIntent, 0);
        remoteControlClient = new RemoteControlClient(mediaPendingIntent);
        remoteControlClient.setTransportControlFlags(
                RemoteControlClient.FLAG_KEY_MEDIA_PLAY |
                        RemoteControlClient.FLAG_KEY_MEDIA_PAUSE |
                        RemoteControlClient.FLAG_KEY_MEDIA_NEXT |
                        RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS);
        getAudioManager().registerRemoteControlClient(remoteControlClient);
    }

    private void registerEverything() {
        if (!thingsRegistered) {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            registerReceiver(receiver, intentFilter);
            getAudioManager().registerMediaButtonEventReceiver(new ComponentName(getApplicationContext(), MediaButtonIntentReceiver.class));
            initRemoteControlClient();
            thingsRegistered = true;
        }
    }

    private void unregisterEverything() {
        if (thingsRegistered) {
            unregisterReceiver(receiver);
            getAudioManager().unregisterRemoteControlClient(remoteControlClient);
            getAudioManager().unregisterMediaButtonEventReceiver(new ComponentName(getApplicationContext(), MediaButtonIntentReceiver.class));
            thingsRegistered = false;
        }
    }

    private void updateRemoteControlClient(Song song) {
        Bitmap loadedImage = ImageLoader.getInstance().loadImageSync(MusicUtil.getAlbumArtUri(song.albumId).toString());
        remoteControlClient
                .editMetadata(false)
                .putString(MediaMetadataRetriever.METADATA_KEY_ARTIST, song.artistName)
                .putString(MediaMetadataRetriever.METADATA_KEY_TITLE, song.title)
                .putLong(MediaMetadataRetriever.METADATA_KEY_DURATION, song.duration)
                .putBitmap(RemoteControlClient.MetadataEditor.BITMAP_KEY_ARTWORK, loadedImage)
                .apply();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        setUpMediaPlayerIfNeeded();
        if (intent != null) {
            if (intent.getAction() != null) {
                String action = intent.getAction();
                switch (action) {
                    case ACTION_TOGGLE_PLAYBACK:
                        if (isPlaying()) {
                            pausePlaying();
                        } else {
                            resumePlaying();
                        }
                        break;
                    case ACTION_PAUSE:
                        pausePlaying();
                        break;
                    case ACTION_PLAY:
                        playSong();
                        break;
                    case ACTION_REWIND:
                        back();
                        break;
                    case ACTION_SKIP:
                        playNextSong();
                        break;
                    case ACTION_STOP:
                        stopPlaying();
                        break;
                    case ACTION_QUIT:
                        killEverythingAndReleaseResources();
                }
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "onBind");
        return musicBind;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        unregisterEverything();
        killEverythingAndReleaseResources();
        return false;
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        notifyOnMusicRemoteEventListeners(MusicRemoteEvent.SONG_COMPLETED);
        if (isLastTrack()) {
            notifyOnMusicRemoteEventListeners(MusicRemoteEvent.QUEUE_COMPLETED);
            notificationHelper.updatePlayState(isPlaying());
            remoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_STOPPED);
            notifyOnMusicRemoteEventListeners(MusicRemoteEvent.STOP);
        } else {
            playNextSong();
        }
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        isPlayerPrepared = false;
        player.reset();
        notifyOnMusicRemoteEventListeners(MusicRemoteEvent.STOP);
        return false;
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        player.start();
        isPlayerPrepared = true;
        notificationHelper.updatePlayState(isPlaying());
        remoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);
        notifyOnMusicRemoteEventListeners(MusicRemoteEvent.PLAY);
        savePosition();
    }

    @Override
    public void onDestroy() {
        unregisterEverything();
        killEverythingAndReleaseResources();
    }

    private void killEverythingAndReleaseResources() {
        savePosition();
        saveQueue();
        stopPlaying();
        notificationHelper.killNotification();
        stopSelf();
    }

    private void setUpMediaPlayerIfNeeded() {
        if (player == null) {
            player = new MediaPlayer();

            player.setOnPreparedListener(this);
            player.setOnCompletionListener(this);
            player.setOnErrorListener(this);

            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        }
    }

    private void updateNotification() {
        notificationHelper.buildNotification(playingQueue.get(position), isPlaying());
    }

    public void setPlayingQueue(List<Song> songs) {
        if (!playingQueue.equals(songs)) {
            this.playingQueue = songs;
            shuffler = new Shuffler(playingQueue.size());
            saveQueue();
        }
    }

    public List<Song> getPlayingQueue() {
        return playingQueue;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public int getPosition() {
        return position;
    }

    public long getCurrentSongId() {
        return currentSongId;
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                // resume playback
                registerEverything();
                player.setVolume(1.0f, 1.0f);
                if (wasPlayingBeforeFocusLoss) {
                    resumePlaying();
                    updateRemoteControlClient(getPlayingQueue().get(position));
                }
                updateRemoteControlClient(getPlayingQueue().get(position));
                break;

            case AudioManager.AUDIOFOCUS_LOSS:
                // Lost focus for an unbounded amount of time: stop playback and release media player
                //TODO maybe also release player
                wasPlayingBeforeFocusLoss = isPlaying();
                pausePlaying();
                unregisterEverything();
                break;

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // Lost focus for a short time, but we have to stop
                // playback. We don't release the media player because playback
                // is likely to resume
                wasPlayingBeforeFocusLoss = isPlaying();
                pausePlaying();
                break;

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // Lost focus for a short time, but it's ok to keep playing
                // at an attenuated level
                player.setVolume(0.2f, 0.2f);
                break;
        }
    }

    public class MusicBinder extends Binder {
        public MusicService getService() {
            return MusicService.this;
        }
    }

    public void playSong() {
        if (requestFocus()) {
            setUpMediaPlayerIfNeeded();
            registerEverything();
            isPlayerPrepared = false;
            player.reset();
            Uri trackUri = getCurrentPositionTrackUri();
            try {
                player.setDataSource(getApplicationContext(), trackUri);
                currentSongId = playingQueue.get(position).id;
                updateNotification();
                updateRemoteControlClient(getPlayingQueue().get(position));
                player.prepareAsync();
            } catch (Exception e) {
                Log.e("MUSIC SERVICE", "Error setting data source", e);
                player.reset();
                Toast.makeText(getApplicationContext(), getResources().getString(R.string.unplayable_file), Toast.LENGTH_SHORT).show();
                notifyOnMusicRemoteEventListeners(MusicRemoteEvent.STOP);
                notificationHelper.updatePlayState(false);
                remoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_STOPPED);
            }
        } else {
            Toast.makeText(this, getResources().getString(R.string.audio_focus_denied), Toast.LENGTH_SHORT).show();
        }
    }

    public void pausePlaying() {
        if (isPlaying()) {
            player.pause();
            notificationHelper.updatePlayState(isPlaying());
            remoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_PAUSED);
            notifyOnMusicRemoteEventListeners(MusicRemoteEvent.PAUSE);
        }
    }

    public void resumePlaying() {
        if (requestFocus()) {
            if (isPlayerPrepared) {
                player.start();
                notificationHelper.updatePlayState(isPlaying());
                remoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);
                notifyOnMusicRemoteEventListeners(MusicRemoteEvent.RESUME);
            } else {
                playSong();
            }
        } else {
            Toast.makeText(this, getResources().getString(R.string.audio_focus_denied), Toast.LENGTH_SHORT).show();
        }
    }

    public void stopPlaying() {
        isPlayerPrepared = false;
        player.stop();
        notificationHelper.updatePlayState(isPlaying());
        remoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_STOPPED);
        player.release();
        player = null;
        notifyOnMusicRemoteEventListeners(MusicRemoteEvent.STOP);
    }

    public void playNextSong() {
        if (position != -1) {
            if (isPlayerPrepared) {

                setPosition(getNextPosition());
                playSong();
                notifyOnMusicRemoteEventListeners(MusicRemoteEvent.NEXT);
            }
        }
    }

    public void playPreviousSong() {
        if (position != -1) {
            setPosition(getPreviousPosition());
            playSong();
            notifyOnMusicRemoteEventListeners(MusicRemoteEvent.PREV);
        }
    }

    public void back() {
        if (position != -1) {
            if (getSongProgressMillis() > 2000) {
                seekTo(0);
            } else {
                playPreviousSong();
            }
        }
    }

    public int getNextPosition() {
        int position = 0;
        switch (repeatMode) {
            case REPEAT_MODE_NONE:
                switch (shuffleMode) {
                    case SHUFFLE_MODE_NONE:
                        position = getPosition() + 1;
                        if (isLastTrack()) {
                            position -= 1;
                        }
                        break;
                    case SHUFFLE_MODE_SHUFFLE:
                        position = getShuffler().nextInt(false);
                        break;
                }
                break;
            case REPEAT_MODE_ALL:
                switch (shuffleMode) {
                    case SHUFFLE_MODE_NONE:
                        position = getPosition() + 1;
                        if (isLastTrack()) {
                            position = 0;
                        }
                        break;
                    case SHUFFLE_MODE_SHUFFLE:
                        position = getShuffler().nextInt(true);
                        break;
                }
                break;
            case REPEAT_MODE_THIS:
                position = getPosition();
                break;
        }
        return position;
    }

    public int getPreviousPosition() {
        int position = 0;
        switch (repeatMode) {
            case REPEAT_MODE_NONE:
                switch (shuffleMode) {
                    case SHUFFLE_MODE_NONE:
                        position = getPosition() - 1;
                        if (position < 0) {
                            position = 0;
                        }
                        break;
                    case SHUFFLE_MODE_SHUFFLE:
                        position = getShuffler().previousInt();
                        break;
                }
                break;
            case REPEAT_MODE_ALL:
                switch (shuffleMode) {
                    case SHUFFLE_MODE_NONE:
                        position = getPosition() - 1;
                        if (position < 0) {
                            position = getPlayingQueue().size() - 1;
                        }
                        break;
                    case SHUFFLE_MODE_SHUFFLE:
                        position = getShuffler().previousInt();
                        break;
                }
                break;
            case REPEAT_MODE_THIS:
                position = getPosition();
                break;
        }
        return position;
    }

    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    private Uri getCurrentPositionTrackUri() {
        return ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, playingQueue.get(position).id);
    }

    public int getSongProgressMillis() {
        return player.getCurrentPosition();
    }

    public int getSongDurationMillis() {
        return player.getDuration();
    }

    public void seekTo(int millis) {
        player.seekTo(millis);
    }

    public boolean isPlayerPrepared() {
        if (player == null) {
            return false;
        }
        return isPlayerPrepared;
    }

    private boolean isLastTrack() {
        return getPosition() == getPlayingQueue().size() - 1;
    }

    private void notifyOnMusicRemoteEventListeners(int event) {
        MusicRemoteEvent musicRemoteEvent = new MusicRemoteEvent(event);
        for (OnMusicRemoteEventListener listener : onMusicRemoteEventListeners) {
            listener.onMusicRemoteEvent(musicRemoteEvent);
        }
    }

    public void addOnMusicRemoteEventListener(OnMusicRemoteEventListener onMusicRemoteEventListener) {
        onMusicRemoteEventListeners.add(onMusicRemoteEventListener);
    }

    public void saveQueue() {
        try {
            InternalStorageUtil.writeObject(MusicService.this, AppKeys.IS_PLAYING_QUEUE, getPlayingQueue());
            Log.i(TAG, "saved current queue state");
        } catch (IOException e) {
            Log.e(TAG, "error while saving music service queue state", e);
        }
    }

    public void savePosition() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    InternalStorageUtil.writeObject(MusicService.this, AppKeys.IS_POSITION_IN_QUEUE, getPosition());
                    Log.i(TAG, "saved current position state");
                } catch (IOException e) {
                    Log.e(TAG, "error while saving music service position state", e);
                }
            }
        }).start();
    }

    public void setShuffleMode(final int shuffleMode) {
        switch (shuffleMode) {
            case SHUFFLE_MODE_SHUFFLE:
                shuffler = new Shuffler(getPlayingQueue().size());
            case SHUFFLE_MODE_NONE:
                this.shuffleMode = shuffleMode;
                PreferenceManager.getDefaultSharedPreferences(this).edit()
                        .putInt(AppKeys.SP_SHUFFLE_MODE, shuffleMode)
                        .apply();
                notifyOnMusicRemoteEventListeners(MusicRemoteEvent.SHUFFLE_MODE_CHANGED);
                break;
        }
    }

    public void setRepeatMode(final int repeatMode) {
        switch (repeatMode) {
            case REPEAT_MODE_NONE:
            case REPEAT_MODE_ALL:
            case REPEAT_MODE_THIS:
                this.repeatMode = repeatMode;
                PreferenceManager.getDefaultSharedPreferences(this).edit()
                        .putInt(AppKeys.SP_REPEAT_MODE, repeatMode)
                        .apply();
                notifyOnMusicRemoteEventListeners(MusicRemoteEvent.REPEAT_MODE_CHANGED);
                break;
        }
    }

    public void cycleRepeatMode() {
        switch (repeatMode) {
            case REPEAT_MODE_NONE:
                setRepeatMode(REPEAT_MODE_ALL);
                break;
            case REPEAT_MODE_ALL:
                setRepeatMode(REPEAT_MODE_THIS);
                break;
            default:
                setRepeatMode(REPEAT_MODE_NONE);
                break;
        }
    }

    public void toggleShuffle() {
        if (shuffleMode == SHUFFLE_MODE_NONE) {
            setShuffleMode(SHUFFLE_MODE_SHUFFLE);
        } else {
            setShuffleMode(SHUFFLE_MODE_NONE);
        }
    }

    public int getRepeatMode() {
        return repeatMode;
    }

    public int getShuffleMode() {
        return shuffleMode;
    }
}

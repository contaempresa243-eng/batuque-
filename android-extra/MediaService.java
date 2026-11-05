package com.aldair.batuque;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import androidx.core.app.NotificationCompat;

/** Notificação do leitor (capa, ⏮ ▶/⏸ ⏭) e serviço que mantém a música a tocar com o ecrã desligado. */
public class MediaService extends Service {
    public interface Sink { void action(String action, double seconds); }

    static final String CH = "batuque_player";
    static final int ID = 4242;
    static final String A_PLAY = "com.aldair.batuque.PLAY";
    static final String A_PAUSE = "com.aldair.batuque.PAUSE";
    static final String A_NEXT = "com.aldair.batuque.NEXT";
    static final String A_PREV = "com.aldair.batuque.PREV";

    static volatile MediaService inst;
    static volatile Sink sink;
    static String title = "", artist = "", album = "";
    static Bitmap cover;
    static boolean playing;
    static long posMs, durMs;

    private MediaSessionCompat session;
    private NotificationManager nm;
    private boolean inFg = false;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH, "Leitor de música", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
        session = new MediaSessionCompat(this, "Batuque");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { emit("play", 0); }
            @Override public void onPause() { emit("pause", 0); }
            @Override public void onStop() { emit("pause", 0); }
            @Override public void onSkipToNext() { emit("nexttrack", 0); }
            @Override public void onSkipToPrevious() { emit("previoustrack", 0); }
            @Override public void onSeekTo(long pos) { emit("seekto", pos / 1000.0); }
        });
        session.setActive(true);
    }

    private void emit(String a, double s) {
        Sink k = sink;
        if (k != null) k.action(a, s);
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        String a = i == null ? null : i.getAction();
        if (a != null) {
            switch (a) {
                case A_PLAY: emit("play", 0); break;
                case A_PAUSE: emit("pause", 0); break;
                case A_NEXT: emit("nexttrack", 0); break;
                case A_PREV: emit("previoustrack", 0); break;
            }
            if (!inFg && nm != null) refresh();
        } else {
            refresh();
        }
        return START_NOT_STICKY;
    }

    private PendingIntent act(String a, int code) {
        Intent i = new Intent(this, MediaService.class).setAction(a);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(this, code, i, fl);
    }

    private Notification build() {
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH)
                .setSmallIcon(android.R.drawable.stat_sys_headset)
                .setContentTitle(title)
                .setContentText(artist)
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(playing)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, "Anterior", act(A_PREV, 1))
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pausa" : "Tocar", act(playing ? A_PAUSE : A_PLAY, 2))
                .addAction(android.R.drawable.ic_media_next, "Seguinte", act(A_NEXT, 3))
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (cover != null) b.setLargeIcon(cover);
        return b.build();
    }

    /** Atualiza sessão (ecrã de bloqueio, auscultadores) e notificação com os dados atuais. */
    void refresh() {
        PlaybackStateCompat ps = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SEEK_TO
                        | PlaybackStateCompat.ACTION_STOP)
                .setState(playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        posMs, playing ? 1f : 0f, SystemClock.elapsedRealtime())
                .build();
        session.setPlaybackState(ps);
        MediaMetadataCompat.Builder mb = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durMs);
        if (cover != null) {
            mb.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cover);
            mb.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, cover);
        }
        session.setMetadata(mb.build());
        session.setActive(true);

        Notification n = build();
        try {
            if (playing || !inFg) {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(ID, n);
                }
                inFg = true;
            } else {
                nm.notify(ID, n);
            }
            if (!playing && inFg) {
                // em pausa a notificação fica, mas pode ser dispensada
                stopForeground(false);
                inFg = false;
                nm.notify(ID, n);
            }
        } catch (Exception e) {
            nm.notify(ID, n);
        }
    }

    void shutdown() {
        try { stopForeground(true); } catch (Exception e) { }
        inFg = false;
        stopSelf();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        inst = null;
        try { session.setActive(false); session.release(); } catch (Exception e) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}

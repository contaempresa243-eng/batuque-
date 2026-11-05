package com.aldair.batuque;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.provider.Settings;
import android.util.Base64;
import android.view.Window;
import android.view.WindowManager;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Ponte entre o leitor (JavaScript) e a notificação nativa + ecrã cheio. */
@CapacitorPlugin(name = "BatuqueMedia")
public class BatuqueMediaPlugin extends Plugin {
    private final Handler main = new Handler(Looper.getMainLooper());

    private AudioManager am;
    private ContentObserver volObs;
    private float lastVol = -1f;

    private float sysVolume() {
        if (am == null) am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return max <= 0 ? 0f : am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
    }

    @Override
    public void load() {
        try {
            volObs = new ContentObserver(main) {
                @Override
                public void onChange(boolean selfChange) {
                    float v = sysVolume();
                    if (Math.abs(v - lastVol) > 0.001f) {
                        lastVol = v;
                        JSObject o = new JSObject();
                        o.put("volume", v);
                        notifyListeners("volume", o);
                    }
                }
            };
            getContext().getContentResolver().registerContentObserver(Settings.System.CONTENT_URI, true, volObs);
        } catch (Exception e) { }
        MediaService.sink = (action, seconds) -> {
            JSObject o = new JSObject();
            o.put("action", action);
            o.put("seekTime", seconds);
            notifyListeners("action", o);
        };
    }

    @PluginMethod
    public void update(PluginCall call) {
        MediaService.title = call.getString("title", "");
        MediaService.artist = call.getString("artist", "");
        MediaService.album = call.getString("album", "");
        MediaService.playing = Boolean.TRUE.equals(call.getBoolean("playing", false));
        MediaService.posMs = (long) (call.getDouble("position", 0.0) * 1000);
        MediaService.durMs = (long) (call.getDouble("duration", 0.0) * 1000);
        if (call.getData().has("cover")) {
            String b64 = call.getString("cover", "");
            if (b64 == null || b64.isEmpty()) {
                MediaService.cover = null;
            } else {
                try {
                    byte[] d = Base64.decode(b64, Base64.DEFAULT);
                    Bitmap bm = BitmapFactory.decodeByteArray(d, 0, d.length);
                    if (bm != null) MediaService.cover = bm;
                } catch (Exception e) { /* mantém a capa anterior */ }
            }
        }
        final Context ctx = getContext().getApplicationContext();
        main.post(() -> {
            try {
                MediaService s = MediaService.inst;
                if (s != null) {
                    s.refresh();
                } else {
                    ContextCompat.startForegroundService(ctx, new Intent(ctx, MediaService.class));
                }
            } catch (Exception e) { /* sem permissão para arrancar em segundo plano */ }
        });
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        main.post(() -> {
            MediaService s = MediaService.inst;
            if (s != null) s.shutdown();
        });
        call.resolve();
    }

    /** Volume de música do telefone (0..1). */
    @PluginMethod
    public void getVolume(PluginCall call) {
        JSObject o = new JSObject();
        try { lastVol = sysVolume(); o.put("volume", lastVol); } catch (Exception e) { o.put("volume", 1.0); }
        call.resolve(o);
    }

    /** Muda o volume de música do telefone (0..1). */
    @PluginMethod
    public void setVolume(PluginCall call) {
        try {
            double v = call.getDouble("volume", 1.0);
            if (am == null) am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int target = (int) Math.round(Math.max(0, Math.min(1, v)) * max);
            lastVol = max <= 0 ? 0f : target / (float) max;
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        } catch (Exception e) { }
        call.resolve();
    }

    /** Cor de fundo da janela e das barras (evita faixas brancas) e cor dos ícones da barra de estado. */
    @PluginMethod
    public void setTheme(PluginCall call) {
        final String col = call.getString("color", "#0d0d0d");
        final boolean dark = Boolean.TRUE.equals(call.getBoolean("dark", true));
        if (getActivity() == null) { call.resolve(); return; }
        getActivity().runOnUiThread(() -> {
            try {
                int c = Color.parseColor(col);
                Window w = getActivity().getWindow();
                w.setBackgroundDrawable(new ColorDrawable(c));
                w.getDecorView().setBackgroundColor(c);
                w.setStatusBarColor(c);
                w.setNavigationBarColor(c);
                WindowInsetsControllerCompat ic = WindowCompat.getInsetsController(w, w.getDecorView());
                if (ic != null) {
                    ic.setAppearanceLightStatusBars(!dark);
                    ic.setAppearanceLightNavigationBars(!dark);
                }
            } catch (Exception e) { }
        });
        call.resolve();
    }

    /** Esconde / mostra a barra de estado e a de navegação e usa também a zona do entalhe da câmara. */
    @PluginMethod
    public void setFullscreen(PluginCall call) {
        final boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
        if (getActivity() == null) { call.resolve(); return; }
        getActivity().runOnUiThread(() -> {
            try {
                Window w = getActivity().getWindow();
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    WindowManager.LayoutParams lp = w.getAttributes();
                    lp.layoutInDisplayCutoutMode = on
                            ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                            : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
                    w.setAttributes(lp);
                }
                WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
                if (c == null) return;
                if (on) {
                    c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    c.hide(WindowInsetsCompat.Type.systemBars());
                } else {
                    c.show(WindowInsetsCompat.Type.systemBars());
                }
            } catch (Exception e) { }
        });
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        try { if (volObs != null) getContext().getContentResolver().unregisterContentObserver(volObs); } catch (Exception e) { }
        super.handleOnDestroy();
    }
}

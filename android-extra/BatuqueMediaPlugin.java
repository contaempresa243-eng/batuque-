package com.aldair.batuque;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Window;
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

    @Override
    public void load() {
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

    /** Esconde / mostra a barra de estado e a barra de navegação. */
    @PluginMethod
    public void setFullscreen(PluginCall call) {
        final boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
        if (getActivity() == null) { call.resolve(); return; }
        getActivity().runOnUiThread(() -> {
            try {
                Window w = getActivity().getWindow();
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
}

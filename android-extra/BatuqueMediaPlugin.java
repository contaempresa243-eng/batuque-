package com.aldair.batuque;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
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
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
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
        final boolean clear = Boolean.TRUE.equals(call.getBoolean("clear", false));
        if (getActivity() == null) { call.resolve(); return; }
        getActivity().runOnUiThread(() -> {
            try {
                int c = clear ? Color.TRANSPARENT : Color.parseColor(col);
                Window w = getActivity().getWindow();
                w.setBackgroundDrawable(new ColorDrawable(c));
                w.getDecorView().setBackgroundColor(c);
                w.setStatusBarColor(c);
                w.setNavigationBarColor(c);
                try { getBridge().getWebView().setBackgroundColor(c); } catch (Exception e2) { }
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
                View decor = w.getDecorView();
                WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, decor);
                if (c == null) return;
                if (on) {
                    // a app desenha por baixo das barras e do entalhe: nenhuma faixa fica por cobrir
                    WindowCompat.setDecorFitsSystemWindows(w, false);
                    ViewCompat.setOnApplyWindowInsetsListener(decor, (v, insets) -> {
                        // com o teclado aberto, a app sobe para o campo de texto continuar visível
                        Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
                        v.setPadding(0, 0, 0, insets.isVisible(WindowInsetsCompat.Type.ime()) ? ime.bottom : 0);
                        return insets;
                    });
                    c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    c.hide(WindowInsetsCompat.Type.systemBars());
                } else {
                    c.show(WindowInsetsCompat.Type.systemBars());
                    ViewCompat.setOnApplyWindowInsetsListener(decor, null);
                    decor.setPadding(0, 0, 0, 0);
                    WindowCompat.setDecorFitsSystemWindows(w, true);
                }
                ViewCompat.requestApplyInsets(decor);
            } catch (Exception e) { }
        });
        call.resolve();
    }


    /** Partilha de texto/link: abre a janela de partilha do Android. */
    @PluginMethod
    public void shareText(PluginCall call) {
        final String title = call.getString("title", "");
        final String text = call.getString("text", "");
        if (getActivity() == null) { call.reject("sem atividade"); return; }
        getActivity().runOnUiThread(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_SUBJECT, title);
                i.putExtra(Intent.EXTRA_TEXT, text);
                getActivity().startActivity(Intent.createChooser(i, title));
                call.resolve();
            } catch (Exception e) { call.reject(String.valueOf(e.getMessage())); }
        });
    }

    /** Recebe um ficheiro em pedaços (base64) e guarda-o na cache, pronto a partilhar. */
    @PluginMethod
    public void shareChunk(PluginCall call) {
        try {
            String name = call.getString("name", "ficheiro");
            boolean reset = Boolean.TRUE.equals(call.getBoolean("reset", false));
            byte[] data = Base64.decode(call.getString("data", ""), Base64.DEFAULT);
            File dir = new File(getContext().getCacheDir(), "share");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, name.replaceAll("[\\\\/:*?\"<>|]", "_"));
            try (FileOutputStream o = new FileOutputStream(f, !reset)) { o.write(data); }
            call.resolve();
        } catch (Exception e) { call.reject(String.valueOf(e.getMessage())); }
    }

    /** Abre a janela de partilha do Android com o ficheiro recebido por shareChunk. */
    @PluginMethod
    public void shareFile(PluginCall call) {
        final String name = call.getString("name", "ficheiro").replaceAll("[\\\\/:*?\"<>|]", "_");
        final String mime = call.getString("mime", "audio/*");
        final String title = call.getString("title", "");
        final String text = call.getString("text", "");
        if (getActivity() == null) { call.reject("sem atividade"); return; }
        getActivity().runOnUiThread(() -> {
            try {
                File f = new File(new File(getContext().getCacheDir(), "share"), name);
                Uri u = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", f);
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(mime);
                i.putExtra(Intent.EXTRA_STREAM, u);
                i.putExtra(Intent.EXTRA_SUBJECT, title);
                i.putExtra(Intent.EXTRA_TEXT, text);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                getActivity().startActivity(Intent.createChooser(i, title));
                call.resolve();
            } catch (Exception e) { call.reject(String.valueOf(e.getMessage())); }
        });
    }

    @Override
    protected void handleOnDestroy() {
        try { if (volObs != null) getContext().getContentResolver().unregisterContentObserver(volObs); } catch (Exception e) { }
        super.handleOnDestroy();
    }
}

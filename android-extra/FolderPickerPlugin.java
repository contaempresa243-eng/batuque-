package com.aldair.batuque;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.provider.MediaStore;
import androidx.core.content.ContextCompat;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import android.content.Intent;
import android.net.Uri;
import androidx.activity.result.ActivityResult;
import androidx.documentfile.provider.DocumentFile;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/** Escolhe uma pasta inteira (com subpastas) e entrega as músicas uma a uma. */
@CapacitorPlugin(
    name = "FolderPicker",
    permissions = {
        @Permission(strings = { Manifest.permission.READ_MEDIA_AUDIO }, alias = "audio33"),
        @Permission(strings = { Manifest.permission.READ_EXTERNAL_STORAGE }, alias = "audioOld")
    }
)
public class FolderPickerPlugin extends Plugin {

    @PluginMethod
    public void pick(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(call, i, "onPicked");
    }

    @ActivityCallback
    private void onPicked(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) {
            call.reject("cancelled");
            return;
        }
        final Uri tree = result.getData().getData();
        new Thread(() -> {
            try {
                DocumentFile root = DocumentFile.fromTreeUri(getContext(), tree);
                if (root == null) { call.reject("erro"); return; }
                JSArray files = new JSArray();
                String name = root.getName() == null ? "Pasta" : root.getName();
                walk(root, name, files);
                JSObject o = new JSObject();
                o.put("name", name);
                o.put("files", files);
                call.resolve(o);
            } catch (Exception e) {
                call.reject(String.valueOf(e.getMessage()));
            }
        }).start();
    }

    private void walk(DocumentFile dir, String path, JSArray out) throws Exception {
        DocumentFile[] list = dir.listFiles();
        for (DocumentFile f : list) {
            String n = f.getName();
            if (n == null) continue;
            if (f.isDirectory()) {
                walk(f, path + "/" + n, out);
            } else {
                String t = f.getType();
                boolean audio = (t != null && t.startsWith("audio/"))
                        || n.toLowerCase().matches(".*\\.(mp3|m4a|aac|flac|wav|ogg|oga|opus|wma|amr)$");
                if (!audio) continue;
                JSObject o = new JSObject();
                o.put("uri", f.getUri().toString());
                o.put("name", n);
                o.put("size", f.length());
                o.put("folder", path);
                out.put(o);
            }
        }
    }

    /** Copia um ficheiro da pasta escolhida para a cache da app (o JS lê e apaga logo a seguir). */
    @PluginMethod
    public void copy(PluginCall call) {
        final String uri = call.getString("uri");
        if (uri == null) { call.reject("sem uri"); return; }
        new Thread(() -> {
            try {
                File dir = new File(getContext().getCacheDir(), "imp");
                dir.mkdirs();
                File out = new File(dir, UUID.randomUUID().toString());
                try (InputStream in = getContext().getContentResolver().openInputStream(Uri.parse(uri));
                     OutputStream os = new FileOutputStream(out)) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) os.write(b, 0, n);
                }
                JSObject o = new JSObject();
                o.put("path", out.getAbsolutePath());
                call.resolve(o);
            } catch (Exception e) {
                call.reject(String.valueOf(e.getMessage()));
            }
        }).start();
    }

    @PluginMethod
    public void clean(PluginCall call) {
        String p = call.getString("path");
        if (p != null) {
            File dir = new File(getContext().getCacheDir(), "imp");
            File f = new File(p);
            if (f.getParentFile() != null && f.getParentFile().equals(dir)) f.delete();
        }
        call.resolve();
    }

    // ---------- Biblioteca automática (MediaStore) ----------
    private String audioAlias() { return Build.VERSION.SDK_INT >= 33 ? "audio33" : "audioOld"; }

    private boolean hasAudio() {
        String perm = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(getContext(), perm) == PackageManager.PERMISSION_GRANTED;
    }

    @PluginMethod
    public void libPermission(PluginCall call) {
        JSObject o = new JSObject();
        o.put("granted", hasAudio());
        call.resolve(o);
    }

    @PluginMethod
    public void libRequest(PluginCall call) {
        if (hasAudio()) {
            JSObject o = new JSObject();
            o.put("granted", true);
            call.resolve(o);
            return;
        }
        requestPermissionForAlias(audioAlias(), call, "onLibPerm");
    }

    @PermissionCallback
    private void onLibPerm(PluginCall call) {
        JSObject o = new JSObject();
        o.put("granted", hasAudio());
        call.resolve(o);
    }

    /** Lista todas as músicas do telefone (sem tocar nos ficheiros). */
    @PluginMethod
    public void scanMusic(PluginCall call) {
        if (!hasAudio()) { call.reject("sem permissao"); return; }
        new Thread(() -> {
            Cursor c = null;
            try {
                String[] proj;
                if (Build.VERSION.SDK_INT >= 29) {
                    proj = new String[] { MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.RELATIVE_PATH };
                } else {
                    proj = new String[] { MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATA };
                }
                String sel = MediaStore.Audio.Media.IS_RINGTONE + "=0 AND " + MediaStore.Audio.Media.IS_NOTIFICATION + "=0 AND " + MediaStore.Audio.Media.IS_ALARM + "=0 AND " + MediaStore.Audio.Media.SIZE + ">100000";
                c = getContext().getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, null, MediaStore.Audio.Media.DISPLAY_NAME + " ASC");
                JSArray files = new JSArray();
                if (c != null) {
                    while (c.moveToNext()) {
                        long id = c.getLong(0);
                        String name = c.getString(1);
                        long size = c.getLong(2);
                        String folder = c.getString(3);
                        if (name == null) continue;
                        if (folder == null) folder = "";
                        if (Build.VERSION.SDK_INT < 29) {
                            int k = folder.lastIndexOf('/');
                            folder = k > 0 ? folder.substring(0, k) : "";
                            folder = folder.replaceFirst("^/storage/[^/]+/", "").replaceFirst("^/sdcard/", "");
                        }
                        while (folder.endsWith("/")) folder = folder.substring(0, folder.length() - 1);
                        JSObject o = new JSObject();
                        o.put("uri", ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString());
                        o.put("name", name);
                        o.put("size", size);
                        o.put("folder", folder);
                        files.put(o);
                    }
                }
                JSObject r = new JSObject();
                r.put("files", files);
                call.resolve(r);
            } catch (Exception e) {
                call.reject(String.valueOf(e.getMessage()));
            } finally {
                if (c != null) c.close();
            }
        }).start();
    }
}

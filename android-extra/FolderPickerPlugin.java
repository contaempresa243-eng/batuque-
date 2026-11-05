package com.aldair.batuque;

import android.app.Activity;
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
@CapacitorPlugin(name = "FolderPicker")
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
}

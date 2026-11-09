package com.aldair.batuque;

import android.content.pm.PackageManager;
import android.os.Build;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(FolderPickerPlugin.class);
        registerPlugin(BatuqueMediaPlugin.class);
        super.onCreate(savedInstanceState);
        try {
            // janela transparente desde o início: mostra o fundo do telemóvel (o JavaScript pode voltar a pintá-la)
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getBridge().getWebView().setBackgroundColor(Color.TRANSPARENT);
        } catch (Exception e) { }
        try {
            // permite que a música volte a tocar a partir da notificação, sem um toque novo no ecrã
            getBridge().getWebView().getSettings().setMediaPlaybackRequiresUserGesture(false);
        } catch (Exception e) { }
        // Android 13+: pedir permissão para mostrar a notificação do leitor
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // mantém o JavaScript a correr em segundo plano (botões da notificação, passar à música seguinte)
        try { getBridge().getWebView().resumeTimers(); } catch (Exception e) { }
    }
}

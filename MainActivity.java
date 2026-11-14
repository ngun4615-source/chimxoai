package vn.chimxoai;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;

public class MainActivity extends Activity {
    WebView web;
    BtBridge bridge;
    Runnable pending;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#fbefd8"));
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true); // localStorage keeps the save game
        s.setAllowFileAccess(true);
        bridge = new BtBridge(this, web);
        web.addJavascriptInterface(bridge, "ChimBT");
        web.setWebViewClient(new WebViewClient());
        web.loadUrl("file:///android_asset/index.html");
    }

    /** Runs r once the Bluetooth runtime permissions are granted. */
    void withPerms(Runnable r) {
        ArrayList<String> need = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.BLUETOOTH_SCAN);
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (need.isEmpty()) { r.run(); return; }
        pending = r;
        requestPermissions(need.toArray(new String[0]), 7);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] res) {
        boolean ok = res.length > 0;
        for (int i = 0; i < res.length; i++) if (res[i] != PackageManager.PERMISSION_GRANTED) ok = false;
        Runnable r = pending; pending = null;
        if (ok && r != null) r.run(); else bridge.emit("state", "error:Chưa cấp quyền Bluetooth");
    }

    @Override
    public void onBackPressed() {
        // let the game's own Back key handling work; the Android back button sends "Back" to the page
        web.evaluateJavascript("window.__pet && window.__pet.press('sr')", null);
    }

    @Override
    protected void onDestroy() { bridge.close(); super.onDestroy(); }
}

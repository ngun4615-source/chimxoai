package vn.chimxoai;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

/**
 * Bluetooth classic SPP (RFCOMM) bridge for the game page.
 * JS -> Java: listen() connect(addr) devices() scan() stopScan() send(line) close()
 * Java -> JS: window.__bt(type, data); type = "state" | "line" | "dev"
 * The wire protocol is plain text lines ending in \n, same as the Nokia version.
 */
@SuppressLint("MissingPermission")
public class BtBridge {
    static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    final MainActivity act;
    final WebView web;
    final Handler ui = new Handler(Looper.getMainLooper());
    final BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
    BluetoothServerSocket server;
    BluetoothSocket sock;
    OutputStream out;
    volatile int gen = 0;
    BroadcastReceiver rx;

    BtBridge(MainActivity a, WebView w) { act = a; web = w; }

    void emit(final String type, final String data) {
        ui.post(new Runnable() {
            public void run() {
                web.evaluateJavascript("window.__bt&&window.__bt(" + JSONObject.quote(type) + "," + JSONObject.quote(data) + ")", null);
            }
        });
    }

    boolean ready() {
        if (ad == null) { emit("state", "error:Máy không có Bluetooth"); return false; }
        if (!ad.isEnabled()) { emit("state", "error:Hãy bật Bluetooth"); return false; }
        return true;
    }

    @JavascriptInterface
    public String devices() {
        JSONArray a = new JSONArray();
        try {
            if (ad != null && ad.isEnabled()) {
                Set<BluetoothDevice> s = ad.getBondedDevices();
                for (BluetoothDevice d : s) {
                    JSONObject o = new JSONObject();
                    o.put("name", d.getName() == null ? d.getAddress() : d.getName());
                    o.put("addr", d.getAddress());
                    a.put(o);
                }
            }
        } catch (Exception e) { }
        return a.toString();
    }

    @JavascriptInterface
    public void scan() {
        act.runOnUiThread(new Runnable() { public void run() { act.withPerms(new Runnable() { public void run() { doScan(); } }); } });
    }

    void doScan() {
        if (!ready()) return;
        stopScan();
        rx = new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                if (BluetoothDevice.ACTION_FOUND.equals(i.getAction())) {
                    BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d == null) return;
                    try {
                        JSONObject o = new JSONObject();
                        o.put("name", d.getName() == null ? d.getAddress() : d.getName());
                        o.put("addr", d.getAddress());
                        emit("dev", o.toString());
                    } catch (Exception e) { }
                }
            }
        };
        act.registerReceiver(rx, new IntentFilter(BluetoothDevice.ACTION_FOUND));
        ad.startDiscovery();
    }

    @JavascriptInterface
    public void stopScan() {
        try { if (ad != null && ad.isDiscovering()) ad.cancelDiscovery(); } catch (Exception e) { }
        if (rx != null) { try { act.unregisterReceiver(rx); } catch (Exception e) { } rx = null; }
    }

    @JavascriptInterface
    public void listen() {
        act.runOnUiThread(new Runnable() { public void run() { act.withPerms(new Runnable() { public void run() { doListen(); } }); } });
    }

    void doListen() {
        if (!ready()) return;
        close();
        final int my = ++gen;
        // make the phone visible so a not-yet-paired friend can find and pair with it
        try {
            Intent d = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            d.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
            act.startActivity(d);
        } catch (Exception e) { }
        new Thread(new Runnable() {
            public void run() {
                try {
                    server = ad.listenUsingRfcommWithServiceRecord("ChimXoai", SPP);
                    emit("state", "listening");
                    BluetoothSocket s = server.accept();
                    try { server.close(); } catch (Exception e) { }
                    if (my != gen) { s.close(); return; }
                    attach(s, my);
                } catch (IOException e) {
                    if (my == gen) emit("state", "error:" + e.getMessage());
                }
            }
        }).start();
    }

    @JavascriptInterface
    public void connect(final String addr) {
        act.runOnUiThread(new Runnable() { public void run() { act.withPerms(new Runnable() { public void run() { doConnect(addr); } }); } });
    }

    void doConnect(final String addr) {
        if (!ready()) return;
        stopScan();
        close();
        final int my = ++gen;
        emit("state", "connecting");
        new Thread(new Runnable() {
            public void run() {
                try {
                    BluetoothDevice d = ad.getRemoteDevice(addr);
                    BluetoothSocket s = d.createRfcommSocketToServiceRecord(SPP);
                    s.connect();
                    if (my != gen) { s.close(); return; }
                    attach(s, my);
                } catch (Exception e) {
                    if (my == gen) emit("state", "error:" + e.getMessage());
                }
            }
        }).start();
    }

    void attach(BluetoothSocket s, int my) {
        sock = s;
        try {
            out = s.getOutputStream();
            InputStream in = s.getInputStream();
            emit("state", "connected");
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) >= 0) {
                if (c == '\n') { if (my == gen) emit("line", sb.toString()); sb.setLength(0); }
                else if (c != '\r' && sb.length() < 200) sb.append((char) c);
            }
        } catch (IOException e) {
        }
        if (my == gen) emit("state", "closed");
    }

    @JavascriptInterface
    public synchronized void send(String line) {
        try {
            if (out != null) { out.write((line + "\n").getBytes("US-ASCII")); out.flush(); }
        } catch (IOException e) { emit("state", "closed"); }
    }

    @JavascriptInterface
    public void close() {
        gen++;
        stopScan();
        try { if (server != null) server.close(); } catch (Exception e) { }
        try { if (sock != null) sock.close(); } catch (Exception e) { }
        server = null; sock = null; out = null;
    }
}

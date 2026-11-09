package ru.venser.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Vensera-База знаний для Android: окно сайта с запретом скриншотов и записи экрана (FLAG_SECURE).
 */
public class MainActivity extends Activity {
    static final String START = "https://bazznania.web.app/";
    static final Set<String> ALLOWED = new HashSet<>(Arrays.asList("bazznania.web.app", "bazznania.firebaseapp.com"));
    // Свой сервер файлов во внутренней сети: доверяем его внутреннему сертификату ТОЛЬКО для этого адреса
    static final Set<String> MEDIA_HOSTS = new HashSet<>(Arrays.asList("192.168.80.133"));
    static final int FILE_REQ = 41;

    // Подключается до загрузки страницы: сайт видит, что открыт в программе
    static final String INJECT =
        "(function(){if(window.VENSER_APP)return;" +
        "window.VENSER_APP={protected:true,platform:'android'," +
        "readClipboard:function(){try{return Promise.resolve(JSON.parse(VenserAndroid.readClipboard()));}catch(e){return Promise.resolve(null);}}};" +
        // Скачивание файлов, которые сайт создаёт сам (blob:/data:) — сохраняем в «Загрузки»
        "document.addEventListener('click',function(ev){var a=ev.target&&ev.target.closest?ev.target.closest('a[href]'):null;if(!a)return;" +
        "var h=a.getAttribute('href')||'';if(!/^(blob:|data:)/.test(h))return;ev.preventDefault();ev.stopImmediatePropagation();" +
        "var name=a.getAttribute('download')||'file';fetch(h).then(function(r){return r.blob();}).then(function(b){var fr=new FileReader();" +
        "fr.onload=function(){var s=String(fr.result);VenserAndroid.saveFile(name,b.type||'application/octet-stream',s.slice(s.indexOf(',')+1));};fr.readAsDataURL(b);})" +
        ".catch(function(){VenserAndroid.toast('Не удалось сохранить файл');});},true);" +
        "})();";

    WebView web;
    ValueCallback<Uri[]> fileCb;
    volatile boolean onAllowedPage = false;
    final Handler ui = new Handler(Looper.getMainLooper());

    static String hostOf(String url) {
        try { String h = Uri.parse(url).getHost(); return h == null ? "" : h; } catch (Exception e) { return ""; }
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // Главное: Android не даёт сделать скриншот или записать экран — вместо содержимого чёрный экран
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setUserAgentString(s.getUserAgentString() + " VENSERApp/" + BuildConfig.VERSION_NAME + " VENSERAndroid");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        WebView.setWebContentsDebuggingEnabled(false);

        web.addJavascriptInterface(new Bridge(), "VenserAndroid");
        boolean early = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
        if (early) {
            Set<String> origins = new HashSet<>(Arrays.asList("https://bazznania.web.app", "https://bazznania.firebaseapp.com"));
            WebViewCompat.addDocumentStartJavaScript(web, INJECT, origins);
        }
        final boolean injectLate = !early;

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                String url = req.getUrl().toString();
                if (ALLOWED.contains(hostOf(url)) || url.startsWith("file:///android_asset/")) return false;
                openExternal(url);
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                onAllowedPage = ALLOWED.contains(hostOf(url));
                if (injectLate && onAllowedPage) v.evaluateJavascript(INJECT, null);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                onAllowedPage = ALLOWED.contains(hostOf(url));
                if (injectLate && onAllowedPage) v.evaluateJavascript(INJECT, null);
            }

            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler h, SslError err) {
                if (MEDIA_HOSTS.contains(hostOf(err.getUrl()))) h.proceed(); else h.cancel();
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) v.loadUrl("file:///android_asset/offline.html");
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    Intent i = params.createIntent();
                    if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(i, FILE_REQ);
                } catch (Exception e) {
                    fileCb = null;
                    Toast.makeText(MainActivity.this, "Не удалось открыть выбор файла", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        web.setDownloadListener((url, ua, disposition, mime, len) -> {
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                String name = URLUtil.guessFileName(url, disposition, mime);
                String js = "fetch('" + url.replace("'", "\\'") + "').then(r=>r.blob()).then(b=>{const fr=new FileReader();fr.onload=()=>{const s=String(fr.result);" +
                    "VenserAndroid.saveFile(" + JSONObject.quote(name) + ",b.type||'application/octet-stream',s.slice(s.indexOf(',')+1));};fr.readAsDataURL(b);});";
                web.evaluateJavascript(js, null);
            } else {
                openExternal(url);
            }
        });

        if (saved != null) web.restoreState(saved); else web.loadUrl(START);
        checkUpdate();
    }

    void openExternal(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != FILE_REQ || fileCb == null) return;
        Uri[] result = null;
        if (res == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                ClipData cd = data.getClipData();
                result = new Uri[cd.getItemCount()];
                for (int i = 0; i < cd.getItemCount(); i++) result[i] = cd.getItemAt(i).getUri();
            } else {
                result = WebChromeClient.FileChooserParams.parseResult(res, data);
            }
        }
        fileCb.onReceiveValue(result);
        fileCb = null;
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    // Мост для сайта: только чтение буфера обмена и сохранение файлов, только на сайте VENSER
    class Bridge {
        @JavascriptInterface
        public String readClipboard() {
            if (!onAllowedPage) return "null";
            final String[] out = {"null"};
            final Object lock = new Object();
            ui.post(() -> {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    JSONObject o = new JSONObject();
                    if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                        ClipData.Item it = cm.getPrimaryClip().getItemAt(0);
                        CharSequence t = it.coerceToText(MainActivity.this);
                        o.put("text", t == null ? "" : t.toString());
                        if (it.getHtmlText() != null) o.put("html", it.getHtmlText());
                    } else o.put("text", "");
                    out[0] = o.toString();
                } catch (Exception ignored) { }
                synchronized (lock) { lock.notifyAll(); }
            });
            synchronized (lock) { try { lock.wait(2000); } catch (InterruptedException ignored) { } }
            return out[0];
        }

        @JavascriptInterface
        public void saveFile(String name, String mime, String b64) {
            if (!onAllowedPage) return;
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                String safe = (name == null || name.trim().isEmpty() ? "file" : name).replaceAll("[\\\\/:*?\"<>|]", "_");
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                    cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) throw new Exception("no uri");
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(data); }
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    try (FileOutputStream fo = new FileOutputStream(new File(dir, safe))) { fo.write(data); }
                }
                toast("Файл сохранён в «Загрузки»: " + safe);
            } catch (Exception e) {
                toast("Не удалось сохранить файл");
            }
        }

        @JavascriptInterface
        public void toast(String msg) {
            ui.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
        }
    }

    // Проверка обновлений: android-version.json в последнем релизе на GitHub
    void checkUpdate() {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(BuildConfig.UPDATE_JSON).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() != 200) return;
                InputStream in = c.getInputStream();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                JSONObject j = new JSONObject(bo.toString("UTF-8"));
                final int code = j.optInt("versionCode", 0);
                final String name = j.optString("versionName", "");
                final String url = j.optString("url", "");
                if (code > BuildConfig.VERSION_CODE && url.startsWith("https://")) {
                    ui.post(() -> {
                        if (isFinishing()) return;
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Обновление Vensera-База знаний")
                            .setMessage("Доступна новая версия " + name + ". Скачать и установить?")
                            .setPositiveButton("Скачать", (d, w) -> openExternal(url))
                            .setNegativeButton("Позже", null)
                            .show();
                    });
                }
            } catch (Exception ignored) { }
        }).start();
    }
}

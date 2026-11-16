package ir.bashiramri.dowr;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String GAME_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final String RELEASE_URL = "https://api.github.com/repos/bashir-84/dowr/releases/latest";

    private WebView webView;
    private long downloadId = -1L;
    private BroadcastReceiver downloadReceiver;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(255, 204, 38));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.addView(webView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(createSplash(), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        configureWebView();
        if (savedInstanceState == null) webView.loadUrl(GAME_URL);
        else webView.restoreState(savedInstanceState);

        mainHandler.postDelayed(() -> {
            View splash = root.findViewWithTag("splash");
            if (splash != null) splash.setVisibility(View.GONE);
        }, 900);

        registerDownloadReceiver();
        checkForUpdate();
    }

    private View createSplash() {
        TextView splash = new TextView(this);
        splash.setTag("splash");
        splash.setText("دور\nنسخه ۱.۱.۰");
        splash.setTextColor(Color.WHITE);
        splash.setTextSize(22);
        splash.setGravity(Gravity.CENTER);
        splash.setBackgroundColor(Color.rgb(255, 204, 38));
        splash.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return splash;
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMediaPlaybackRequiresUserGesture(false);

        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();
        webView.setWebViewClient(new WebViewClientCompat() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !request.getUrl().toString().startsWith("https://appassets.androidplatform.net/assets/");
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
    }

    private void checkForUpdate() {
        networkExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(RELEASE_URL).openConnection();
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(7000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "DOWR-Android");
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return;

                String response = readText(connection.getInputStream());
                JSONObject release = new JSONObject(response);
                String remoteVersion = release.optString("tag_name", "").replaceFirst("^[vV]", "");
                if (!isNewer(remoteVersion, BuildConfig.VERSION_NAME)) return;

                JSONArray assets = release.optJSONArray("assets");
                if (assets == null) return;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String url = asset.optString("browser_download_url", "");
                    if (url.toLowerCase(Locale.US).endsWith(".apk")) {
                        String name = asset.optString("name", "dowr-update.apk");
                        long size = asset.optLong("size", 0);
                        mainHandler.post(() -> showUpdateDialog(remoteVersion, name, url, size));
                        return;
                    }
                }
            } catch (Exception ignored) {
                // Offline, GitHub unavailable, or no published release: keep playing normally.
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void showUpdateDialog(String version, String fileName, String url, long bytes) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
            .setTitle("آپدیت جدید دور")
            .setMessage("نسخه " + version + " آماده است.\nحجم دانلود: " + formatSize(bytes))
            .setNegativeButton("بعداً", null)
            .setPositiveButton("دانلود و نصب", (dialog, which) -> downloadUpdate(fileName, url))
            .show();
    }

    private void downloadUpdate(String fileName, String url) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "اجازه نصب از این برنامه را فعال کنید، سپس دوباره «دانلود و نصب» را بزنید.", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        }

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setTitle("آپدیت دور");
        request.setDescription("در حال دریافت نسخه جدید");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName);
        downloadId = ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
        Toast.makeText(this, "دانلود آپدیت شروع شد.", Toast.LENGTH_SHORT).show();
    }

    private void registerDownloadReceiver() {
        downloadReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                if (completedId != downloadId) return;
                DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
                android.database.Cursor cursor = manager.query(query);
                if (cursor != null && cursor.moveToFirst()) {
                    int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_SUCCESSFUL) installDownloadedApk();
                    else Toast.makeText(MainActivity.this, "دانلود آپدیت کامل نشد.", Toast.LENGTH_LONG).show();
                    cursor.close();
                }
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(downloadReceiver, filter);
    }

    private void installDownloadedApk() {
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        Uri downloadedUri = manager.getUriForDownloadedFile(downloadId);
        if (downloadedUri == null) return;

        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(downloadedUri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(install);
    }

    private static String readText(InputStream stream) throws Exception {
        java.util.Scanner scanner = new java.util.Scanner(stream, "UTF-8").useDelimiter("\\A");
        String result = scanner.hasNext() ? scanner.next() : "";
        scanner.close();
        return result;
    }

    private static boolean isNewer(String remote, String local) {
        String[] r = remote.split("\\.");
        String[] l = local.split("\\.");
        int count = Math.max(r.length, l.length);
        for (int i = 0; i < count; i++) {
            int rv = i < r.length ? Integer.parseInt(r[i].replaceAll("\\D.*", "")) : 0;
            int lv = i < l.length ? Integer.parseInt(l[i].replaceAll("\\D.*", "")) : 0;
            if (rv != lv) return rv > lv;
        }
        return false;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) return Math.max(1, Math.round(bytes / 1024f)) + " کیلوبایت";
        return String.format(Locale.US, "%.1f مگابایت", bytes / 1024f / 1024f);
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (downloadReceiver != null) unregisterReceiver(downloadReceiver);
        networkExecutor.shutdownNow();
        if (webView != null) {
            ((ViewGroup) webView.getParent()).removeView(webView);
            webView.destroy();
        }
        super.onDestroy();
    }
}

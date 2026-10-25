package com.utkarsh.cleanbrowser;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String HOME = "https://duckduckgo.com";

    private final AdBlocker blocker = new AdBlocker();
    private WebView webView;
    private EditText urlBar;
    private TextView shield;
    private ProgressBar progress;
    private LinearLayout content;
    private FrameLayout fullscreen;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private LinearLayout popupLayer;
    private TextView popupTitle;
    private WebView popup;

    private int blockedCount = 0;
    private String lastBlockedRedirect = null;
    private String guardScript = "";
    private boolean guardAtDocStart = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        guardScript = readAsset("guard.js");
        buildUi();
        setupWebView();

        blocker.load(this, () -> runOnUiThread(() ->
                Toast.makeText(this, "Ad block ready (" + blocker.size() + " domains)",
                        Toast.LENGTH_SHORT).show()));

        Uri data = getIntent() != null ? getIntent().getData() : null;
        if (savedInstanceState != null) webView.restoreState(savedInstanceState);
        else webView.loadUrl(data != null ? data.toString() : HOME);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getData() != null) webView.loadUrl(intent.getData().toString());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    // ---------------------------------------------------------------- UI

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private TextView barButton(String label, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(Color.parseColor("#EEEEEE"));
        b.setTextSize(20);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setOnClickListener(click);
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        return b;
    }

    private void buildUi() {
        getWindow().setStatusBarColor(Color.parseColor("#2A2C33"));

        FrameLayout root = new FrameLayout(this);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#2A2C33"));
        bar.setPadding(dp(4), dp(6), dp(4), dp(6));

        bar.addView(barButton("←", v -> { if (webView.canGoBack()) webView.goBack(); }));
        bar.addView(barButton("⟳", v -> webView.reload()));

        urlBar = new EditText(this);
        urlBar.setSingleLine(true);
        urlBar.setHint("Search or type a website");
        urlBar.setHintTextColor(Color.parseColor("#888888"));
        urlBar.setTextColor(Color.parseColor("#EEEEEE"));
        urlBar.setTextSize(14);
        urlBar.setSelectAllOnFocus(true);
        urlBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBar.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Color.parseColor("#15161A"));
        pill.setCornerRadius(dp(20));
        urlBar.setBackground(pill);
        urlBar.setLayoutParams(new LinearLayout.LayoutParams(0, dp(40), 1f));
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                go(urlBar.getText().toString());
                return true;
            }
            return false;
        });
        bar.addView(urlBar);

        shield = barButton("🛡0", v -> onShieldTapped());
        shield.setTextSize(14);
        shield.setTextColor(Color.parseColor("#6FDC8C"));
        bar.addView(shield);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        webView = new WebView(this);
        webView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        content.addView(bar);
        content.addView(progress);
        content.addView(webView);

        fullscreen = new FrameLayout(this);
        fullscreen.setBackgroundColor(Color.BLACK);
        fullscreen.setVisibility(View.GONE);

        // Pop-up windows you opened yourself (sign-in, payment pages) appear here on top.
        popupLayer = new LinearLayout(this);
        popupLayer.setOrientation(LinearLayout.VERTICAL);
        popupLayer.setBackgroundColor(Color.parseColor("#2A2C33"));
        popupLayer.setVisibility(View.GONE);
        popupLayer.setClickable(true);
        LinearLayout popupBar = new LinearLayout(this);
        popupBar.setOrientation(LinearLayout.HORIZONTAL);
        popupBar.setGravity(Gravity.CENTER_VERTICAL);
        popupBar.setPadding(dp(12), dp(6), dp(4), dp(6));
        popupTitle = new TextView(this);
        popupTitle.setTextColor(Color.parseColor("#EEEEEE"));
        popupTitle.setTextSize(14);
        popupTitle.setSingleLine(true);
        popupTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        popupBar.addView(popupTitle);
        popupBar.addView(barButton("✕", v -> closePopup()));
        popupLayer.addView(popupBar);

        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        root.addView(popupLayer, new FrameLayout.LayoutParams(-1, -1));
        root.addView(fullscreen, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    private void go(String text) {
        text = text.trim();
        if (text.isEmpty()) return;
        String target;
        if (text.matches("(?i)^https?://.*")) target = text;
        else if (text.matches("^[\\w-]+(\\.[\\w-]+)+(/.*)?$")) target = "https://" + text;
        else target = "https://duckduckgo.com/?q=" + Uri.encode(text);
        webView.loadUrl(target);
        webView.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
    }

    private void blocked() {
        runOnUiThread(() -> {
            blockedCount++;
            shield.setText(lastBlockedRedirect != null ? "🛡" + blockedCount + "!" : "🛡" + blockedCount);
        });
    }

    private void onShieldTapped() {
        String msg = blockedCount + " ads, trackers and pop-ups blocked on this session.";
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("Clean Browser");
        if (lastBlockedRedirect != null) {
            final String url = lastBlockedRedirect;
            msg += "\n\nLast blocked redirect:\n" + url + "\n\nOpen it only if you expected this page.";
            b.setNeutralButton("Open anyway", (d, w) -> {
                lastBlockedRedirect = null;
                allowOnce = url;
                webView.loadUrl(url);
            });
        }
        b.setMessage(msg).setPositiveButton("OK", null).show();
    }

    private String allowOnce = null;

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(true);                 // so we can inspect & deny new windows
        s.setJavaScriptCanOpenWindowsAutomatically(false); // no automatic pop-ups
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setGeolocationEnabled(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);

        // Run the page guard before any of the site's own scripts (where supported).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, guardScript, Collections.singleton("*"));
            guardAtDocStart = true;
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                if (blocker.isAd(req.getUrl().toString())) {
                    blocked();
                    return new WebResourceResponse("text/plain", "utf-8",
                            new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                String scheme = req.getUrl().getScheme();

                if (url.equals(allowOnce)) { allowOnce = null; return false; }

                // tel:, mailto:, upi://, whatsapp://, intent:// etc.
                // You tapped it -> ask before opening the app. A script did it -> block.
                if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
                    if (req.hasGesture()) openExternal(url);
                    else blocked();
                    return true;
                }
                if (blocker.isAd(url)) {
                    blocked();
                    return true;
                }
                // A script silently sending you to a different website (no tap from you) = ad redirect.
                String current = view.getUrl();
                if (req.isForMainFrame() && !req.hasGesture() && !req.isRedirect() && current != null
                        && current.startsWith("http")
                        && !AdBlocker.baseDomain(current).equals(AdBlocker.baseDomain(url))) {
                    lastBlockedRedirect = url;
                    blocked();
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (!urlBar.hasFocus()) urlBar.setText(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!urlBar.hasFocus()) urlBar.setText(url);
                if (!guardAtDocStart) view.evaluateJavascript(guardScript, null);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }

            // Pop-ups: windows a script opens on its own are blocked. A window you
            // opened with a tap (Google/UPI/bank sign-in, payment pages) opens in a
            // closable layer on top, so the page that opened it keeps working.
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                if (!isUserGesture) {
                    blocked();
                    return false;
                }
                WebView child = createPopup();
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(child);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.deny(); // no camera / mic for websites
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                cb.invoke(origin, false, false);
            }

            // Full-screen video (landscape)
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customCallback = callback;
                fullscreen.addView(view, new FrameLayout.LayoutParams(-1, -1));
                fullscreen.setVisibility(View.VISIBLE);
                content.setVisibility(View.GONE);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });

        // Downloads: always ask first (many sites use fake download buttons).
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
            if (blocker.isAd(url)) { blocked(); return; }
            String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
            new AlertDialog.Builder(this)
                    .setTitle("Download this file?")
                    .setMessage(name + "\n\nOnly download from sites you trust. Never install unknown .apk files.")
                    .setPositiveButton("Download", (d, w) -> {
                        try {
                            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                            r.addRequestHeader("User-Agent", userAgent);
                            String cookie = CookieManager.getInstance().getCookie(url);
                            if (cookie != null) r.addRequestHeader("Cookie", cookie);
                            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
                            Toast.makeText(this, "Downloading " + name, Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(this, "Can't download this file", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    private void exitFullscreen() {
        if (customView == null) return;
        fullscreen.removeView(customView);
        fullscreen.setVisibility(View.GONE);
        content.setVisibility(View.VISIBLE);
        customView = null;
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) exitFullscreen();
        else if (popup != null) {
            if (popup.canGoBack()) popup.goBack();
            else closePopup();
        }
        else if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        closePopup();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- Pop-up window

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createPopup() {
        closePopup();
        WebView child = new WebView(this);
        WebSettings cs = child.getSettings();
        cs.setJavaScriptEnabled(true);
        cs.setDomStorageEnabled(true);
        cs.setUseWideViewPort(true);
        cs.setLoadWithOverviewMode(true);
        cs.setSupportMultipleWindows(false);
        cs.setJavaScriptCanOpenWindowsAutomatically(false);
        cs.setMediaPlaybackRequiresUserGesture(true);
        cs.setGeolocationEnabled(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(child, false);

        child.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                if (blocker.isAd(req.getUrl().toString())) {
                    blocked();
                    return new WebResourceResponse("text/plain", "utf-8",
                            new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                String scheme = req.getUrl().getScheme();
                if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
                    if (req.hasGesture()) openExternal(url);
                    else blocked();
                    return true;
                }
                if (blocker.isAd(url)) {
                    blocked();
                    closePopup();
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (blocker.isAd(url)) {          // an ad that hijacked your tap
                    blocked();
                    closePopup();
                    return;
                }
                Uri u = Uri.parse(url);
                popupTitle.setText(u.getHost() != null ? u.getHost() : url);
            }
        });
        child.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onCloseWindow(WebView window) {
                closePopup();                     // the site closed its own pop-up
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.deny();
            }
        });

        popup = child;
        popupTitle.setText("Loading…");
        popupLayer.addView(child, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        popupLayer.setVisibility(View.VISIBLE);
        return child;
    }

    private void closePopup() {
        if (popup == null) return;
        WebView p = popup;
        popup = null;
        popupLayer.removeView(p);
        popupLayer.setVisibility(View.GONE);
        webView.post(p::destroy);
    }

    // ---------------------------------------------------------------- Other apps

    /** Asks before handing a tapped tel:/mailto:/upi:/intent: link to another app. */
    private void openExternal(String url) {
        String scheme = Uri.parse(url).getScheme();
        if (scheme == null) { blocked(); return; }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (scheme.equals("about") || scheme.equals("javascript") || scheme.equals("data")
                || scheme.equals("blob") || scheme.equals("file") || scheme.equals("content")) {
            blocked();
            return;
        }

        final Intent intent;
        try {
            if (scheme.equals("intent")) {
                intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                intent.setComponent(null);        // never let a page pick a private app screen
                intent.setSelector(null);
            } else {
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            }
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
        } catch (Exception e) {
            blocked();
            return;
        }
        final String fallback = intent.getStringExtra("browser_fallback_url");

        String what;
        switch (scheme) {
            case "tel": what = "Call " + url.substring(4); break;
            case "mailto": what = "Send an email to " + url.substring(7); break;
            case "upi": what = "Pay with a UPI app"; break;
            case "whatsapp": what = "Open WhatsApp"; break;
            case "market": what = "Open the Play Store"; break;
            default:
                what = "Open " + (intent.getPackage() != null ? intent.getPackage() : scheme + ": link")
                        + " in another app";
        }

        new AlertDialog.Builder(this)
                .setTitle("Open another app?")
                .setMessage(what + "\n\nOnly continue if you meant to.")
                .setPositiveButton("Open", (d, w) -> {
                    try {
                        startActivity(intent);
                    } catch (ActivityNotFoundException e) {
                        if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) {
                            webView.loadUrl(fallback);
                        } else {
                            Toast.makeText(this, "No app on this phone can open that link",
                                    Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        Toast.makeText(this, "Can't open that link", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            byte[] buf = new byte[in.available()];
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
            return new String(buf, 0, read, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}

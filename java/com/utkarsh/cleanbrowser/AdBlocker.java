package com.utkarsh.cleanbrowser;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Domain-based ad / tracker / pop-under blocker.
 * Downloads well-known community block lists (refreshed every 3 days, cached offline)
 * and also ships with a built-in list of the big pop-up ad networks.
 */
public class AdBlocker {

    private static final String[] LISTS = {
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            "https://adaway.org/hosts.txt",
    };

    // Always blocked, even before the lists finish downloading.
    private static final String[] BUILT_IN = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "popads.net", "popcash.net", "propellerads.com",
            "onclickads.net", "adsterra.com", "exoclick.com", "juicyads.com",
            "trafficjunky.net", "hilltopads.net", "admaven.com", "clickadu.com",
            "adcash.com", "monetag.com", "realsrv.com", "taboola.com", "outbrain.com",
            "mgid.com", "revcontent.com",
    };

    private static final long REFRESH_MS = 3L * 24 * 60 * 60 * 1000;

    private final Set<String> hosts = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public AdBlocker() {
        Collections.addAll(hosts, BUILT_IN);
    }

    public int size() {
        return hosts.size();
    }

    /** Loads the cached list immediately, then refreshes it in the background if stale. */
    public void load(Context ctx, Runnable onDone) {
        new Thread(() -> {
            File cache = new File(ctx.getFilesDir(), "hosts.txt");
            if (cache.exists()) parse(cache);

            boolean stale = !cache.exists()
                    || System.currentTimeMillis() - cache.lastModified() > REFRESH_MS;
            if (stale) {
                File tmp = new File(ctx.getFilesDir(), "hosts.tmp");
                boolean gotAny = false;
                try (OutputStream out = new FileOutputStream(tmp)) {
                    for (String list : LISTS) {
                        try {
                            download(list, out);
                            out.write('\n');
                            gotAny = true;
                        } catch (Exception ignored) {
                            // one list failing shouldn't stop the others
                        }
                    }
                } catch (Exception ignored) {
                }
                if (gotAny && tmp.renameTo(cache)) parse(cache);
            }
            if (onDone != null) onDone.run();
        }).start();
    }

    private static void download(String address, OutputStream out) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        try (InputStream in = conn.getInputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
    }

    private void parse(File file) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file)))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int hash = line.indexOf('#');
                if (hash > 0) line = line.substring(0, hash).trim();
                String[] parts = line.split("\\s+");
                if (parts.length < 2) continue;
                if (!parts[0].equals("0.0.0.0") && !parts[0].equals("127.0.0.1")) continue;
                String host = parts[1].toLowerCase(Locale.ROOT);
                if (host.equals("localhost") || host.equals("0.0.0.0")
                        || host.equals("local") || !host.contains(".")) continue;
                hosts.add(host);
            }
        } catch (Exception ignored) {
        }
    }

    /** True if the URL's host, or any parent domain of it, is on a block list. */
    public boolean isAd(String url) {
        if (url == null) return false;
        String host;
        try {
            host = Uri.parse(url).getHost();
        } catch (Exception e) {
            return false;
        }
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        while (host.contains(".")) {
            if (hosts.contains(host)) return true;
            host = host.substring(host.indexOf('.') + 1);
        }
        return false;
    }

    /** "m.example.co.in" -> "example.co.in", "a.b.example.com" -> "example.com" */
    public static String baseDomain(String url) {
        String host;
        try {
            host = Uri.parse(url).getHost();
        } catch (Exception e) {
            return "";
        }
        if (host == null) return "";
        String[] p = host.toLowerCase(Locale.ROOT).split("\\.");
        if (p.length <= 2) return host;
        String last = p[p.length - 1], second = p[p.length - 2];
        boolean twoPartTld = last.length() == 2 && second.length() <= 3;
        int keep = twoPartTld ? 3 : 2;
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, p.length - keep); i < p.length; i++) {
            if (sb.length() > 0) sb.append('.');
            sb.append(p[i]);
        }
        return sb.toString();
    }
}

package io.github.c0ldsheep.sanket;

import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.MimeTypeMap;
import io.github.c0ldsheep.sanket.core.Backoff;
import io.github.c0ldsheep.sanket.core.ByteRanges;
import io.github.c0ldsheep.sanket.core.FileNames;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Downloads that survive signal loss.
 *
 * <p>Bytes go straight into the file's place in Downloads/SANKET, as a pending entry other apps
 * cannot see yet, so a finished file needs no copy and no double space. What has arrived is
 * synced to storage every second or so, and at once whenever SANKET expects a drop. When the
 * connection breaks the download waits instead of failing; when the network is back it asks only
 * for the missing bytes, guarded by the ETag or Last-Modified date so that a changed file is
 * never stitched onto an old one. Busy or unreachable servers are tried again by themselves,
 * further apart each time.
 */
final class Transfers {
    enum State { QUEUED, RUNNING, WAITING, DONE, FAILED }

    /** Why a download is waiting or stopped. The screen turns this into words. */
    enum Problem {
        NONE, RETRYING, NO_NETWORK, WIFI_ONLY, SERVER_UNREACHABLE, SERVER_BUSY, LINK_EXPIRED, NOT_FOUND, NOT_A_FILE,
        NO_SPACE, HTTP_ERROR
    }

    /** Why there is no network on purpose, so "waiting" can say what to do. */
    enum Offline { NONE, DATA_OFF, AIRPLANE }

    enum Added { ADDED, DUPLICATE, NOT_HTTPS }

    interface Listener {
        void onLatency(double millis);

        void onFinished(Item it);

        void onChanged();
    }

    /** One download. Its worker thread writes the fields; the screen only reads them. */
    static final class Item {
        final String id;
        final long createdMs;
        final AtomicBoolean active = new AtomicBoolean();
        volatile String url;
        volatile String name;
        /** The pending or finished entry in Downloads, or "" before the first byte. */
        volatile String uri = "";
        volatile long size = -1L;
        /** Bytes known to be safely on storage. */
        volatile long durable;
        /** Bytes written in the current attempt, for the progress bar. */
        volatile long received;
        volatile String etag = "";
        volatile String lastModified = "";
        volatile String mime = "";
        volatile State state = State.QUEUED;
        volatile Problem problem = Problem.NONE;
        volatile int code;
        volatile long retryAtMs;
        volatile long needBytes;
        volatile long freeBytes;
        volatile int quickRetries;
        volatile int attempts;
        volatile int resumes;
        volatile boolean wifiOnly;
        volatile boolean cancelled;
        volatile long finishedMs;

        Item(String id, String url, String name, long createdMs) {
            this.id = id;
            this.url = url;
            this.name = name;
            this.createdMs = createdMs;
        }

        long shown() { return state == State.RUNNING ? Math.max(received, durable) : durable; }

        /** Progress in tenths of a percent, or -1 while the size is unknown. */
        int permille() {
            long total = size;
            return total > 0 ? (int) Math.min(1000L, shown() * 1000L / total) : -1;
        }

        boolean busy() { return state == State.QUEUED || state == State.RUNNING || state == State.WAITING; }
    }

    static final String FOLDER = Environment.DIRECTORY_DOWNLOADS + "/SANKET";
    private static final String TAG = "SanketTransfers";
    private static final int QUICK_RETRIES = 3;
    private static final long QUICK_RETRY_S = 5L;
    private static final long SYNC_BYTES = 1L << 20;
    private static final long SYNC_EVERY_MS = 1_000L;
    private static final long PERSIST_EVERY_MS = 2_000L;
    private static final long SPACE_MARGIN = 20L << 20;
    private static final int BUFFER = 64 * 1024;

    private final ContentResolver resolver;
    private final StorageManager storage;
    private final Stores stores;
    private final Listener listener;
    private final List<Item> items = new CopyOnWriteArrayList<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong checkpoint = new AtomicLong();
    private final Object saveLock = new Object();
    private volatile long persistedAtMs;
    private volatile boolean networkOk = true;
    private volatile boolean unmetered;
    private volatile Offline offline = Offline.NONE;

    Transfers(Context ctx, Stores stores, Listener listener) {
        resolver = ctx.getContentResolver();
        storage = ctx.getSystemService(StorageManager.class);
        this.stores = stores;
        this.listener = listener;
        for (Item it : stores.loadTransfers()) {
            if (it.state == State.QUEUED || it.state == State.RUNNING) it.state = State.WAITING;
            // Version 0.1 kept partial files elsewhere; those downloads start again.
            if (it.state != State.DONE && it.uri.isEmpty()) it.durable = 0L;
            it.received = it.durable;
            items.add(it);
        }
        deleteOldParts(new File(ctx.getFilesDir(), "downloads"));
    }

    /** Starts downloading an https link. */
    Added add(String link, boolean wifiOnly) {
        Uri u = Uri.parse(link == null ? "" : link.trim());
        if (!secure(u)) return Added.NOT_HTTPS;
        String url = u.toString();
        for (Item it : items) {
            if (it.url.equals(url) && it.state != State.DONE) return Added.DUPLICATE;
        }
        Item it = new Item(UUID.randomUUID().toString(), url, FileNames.choose(null, u.getLastPathSegment(), null),
                System.currentTimeMillis());
        it.wifiOnly = wifiOnly;
        items.add(0, it);
        persist(true);
        start(it);
        listener.onChanged();
        return Added.ADDED;
    }

    List<Item> items() { return items; }

    Item find(String id) {
        for (Item it : items) {
            if (it.id.equals(id)) return it;
        }
        return null;
    }

    /** Anything still to finish. */
    boolean busy() {
        for (Item it : items) {
            if (it.busy()) return true;
        }
        return false;
    }

    /** Anything moving bytes right now. */
    boolean running() {
        for (Item it : items) {
            if (it.state == State.RUNNING || it.state == State.QUEUED) return true;
        }
        return false;
    }

    /** True when there are unfinished downloads and every one of them waits for Wi-Fi. */
    boolean onlyWifiWaiting() {
        boolean any = false;
        for (Item it : items) {
            if (!it.busy()) continue;
            if (!it.wifiOnly) return false;
            any = true;
        }
        return any;
    }

    /** Running downloads sync to storage at their next block. Cheap; called on every warning. */
    void checkpointAll() { checkpoint.incrementAndGet(); }

    void onNetwork(boolean working, boolean unmeteredNow) {
        boolean back = working && !networkOk;
        boolean wifiBack = working && unmeteredNow && !unmetered;
        networkOk = working;
        unmetered = working && unmeteredNow;
        if (back || wifiBack) resumeWaiting();
    }

    void setOffline(Offline reason) { offline = reason; }

    Offline offline() { return offline; }

    void resumeWaiting() {
        for (Item it : items) {
            if (it.state == State.WAITING) {
                it.quickRetries = 0;
                start(it);
            }
        }
    }

    /** The user asked to try again now. */
    void retry(Item it) {
        it.quickRetries = 0;
        it.attempts = 0;
        it.problem = Problem.NONE;
        if (it.state == State.FAILED) it.state = State.WAITING;
        start(it);
        persist(true);
        listener.onChanged();
    }

    /** Continues with a fresh link, for example after the old one expired. False if it is not https. */
    boolean replaceUrl(Item it, String link) {
        Uri u = Uri.parse(link == null ? "" : link.trim());
        if (!secure(u)) return false;
        it.url = u.toString();
        retry(it);
        return true;
    }

    /** Stops an unfinished download and deletes what was saved of it. */
    void cancel(Item it) {
        it.cancelled = true;
        items.remove(it);
        String uri = it.uri;
        if (it.state != State.DONE && !uri.isEmpty()) {
            try {
                pool.execute(() -> deleteEntry(uri));
            } catch (RejectedExecutionException e) {
                deleteEntry(uri);
            }
        }
        persist(true);
        listener.onChanged();
    }

    /** Removes a finished download from the list. The file stays in Downloads. */
    void clear(Item it) {
        items.remove(it);
        persist(true);
        listener.onChanged();
    }

    void clearFinished() {
        for (Item it : items) {
            if (it.state == State.DONE) items.remove(it);
        }
        persist(true);
        listener.onChanged();
    }

    /** For "delete my data": unfinished parts are deleted, finished files stay in Downloads. */
    void clearAll() {
        for (Item it : items) {
            it.cancelled = true;
            if (it.state != State.DONE && !it.uri.isEmpty()) deleteEntry(it.uri);
        }
        items.clear();
        listener.onChanged();
    }

    /** Opens a finished file in whatever app handles it, or the Downloads app for old entries. */
    static Intent viewIntent(Item it) {
        if (it.uri.isEmpty()) return new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(Uri.parse(it.uri), it.mime.isEmpty() ? null : it.mime);
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return view;
    }

    private void start(Item it) {
        if (it.cancelled || it.state == State.DONE) return;
        if (it.wifiOnly && !unmetered) {
            if (it.state != State.FAILED && it.state != State.RUNNING) {
                it.state = State.WAITING;
                it.problem = Problem.WIFI_ONLY;
            }
            return;
        }
        if (!it.active.compareAndSet(false, true)) return;
        it.state = State.QUEUED;
        try {
            pool.execute(() -> run(it));
        } catch (RejectedExecutionException e) {
            it.active.set(false);
        }
    }

    private void run(Item it) {
        HttpURLConnection c = null;
        try {
            if (it.cancelled) return;
            it.state = State.RUNNING;
            it.problem = Problem.NONE;
            Uri target = existingTarget(it);
            long have = target == null ? 0L : it.durable;
            it.received = have;
            c = (HttpURLConnection) new URL(it.url).openConnection();
            c.setConnectTimeout(15_000);
            c.setReadTimeout(20_000);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (have > 0) {
                c.setRequestProperty("Range", "bytes=" + have + "-");
                String validator = !it.etag.isEmpty() && !it.etag.startsWith("W/") ? it.etag : it.lastModified;
                if (!validator.isEmpty()) c.setRequestProperty("If-Range", validator);
            }
            long started = SystemClock.elapsedRealtime();
            int code = c.getResponseCode();
            listener.onLatency(SystemClock.elapsedRealtime() - started);
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                long first = ByteRanges.first(c.getHeaderField("Content-Range"));
                long total = ByteRanges.total(c.getHeaderField("Content-Range"));
                boolean sameFile = it.size <= 0 || total <= 0 || total == it.size;
                if (first != have || !sameFile) {
                    if (have == 0) {
                        fail(it, Problem.HTTP_ERROR, code);
                        return;
                    }
                    it.durable = 0L;   // a different range or a changed file: start clean
                    throw new IOException("Server sent another range; starting again");
                }
                if (have > 0) it.resumes++;
            } else if (code == HttpURLConnection.HTTP_OK) {
                String type = c.getContentType();
                if (isWebPage(type, c.getURL())) {
                    fail(it, Problem.NOT_A_FILE, code);
                    return;
                }
                it.etag = header(c, "ETag");
                it.lastModified = header(c, "Last-Modified");
                it.mime = type == null ? "" : mimeOnly(type);
                if (target == null) {
                    it.name = FileNames.choose(c.getHeaderField("Content-Disposition"),
                            Uri.parse(c.getURL().toString()).getLastPathSegment(), extensionFor(it.mime));
                }
                have = 0L;
            } else if (code == 416 && target != null && it.size > 0 && have >= it.size) {
                finish(it, target);
                return;
            } else if (Backoff.retryable(code)) {
                waitAndRetry(it, Problem.SERVER_BUSY, code, Backoff.retryAfterS(c.getHeaderField("Retry-After")));
                return;
            } else if (code == 401 || code == 403 || code == 410) {
                fail(it, Problem.LINK_EXPIRED, code);
                return;
            } else if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                fail(it, Problem.NOT_FOUND, code);
                return;
            } else {
                fail(it, Problem.HTTP_ERROR, code);
                return;
            }
            long total = code == HttpURLConnection.HTTP_PARTIAL ? ByteRanges.total(c.getHeaderField("Content-Range"))
                    : c.getContentLengthLong();
            if (total > 0) it.size = total;
            if (it.size > 0) {
                long need = it.size - have;
                long free = freeBytes();
                if (free >= 0 && need + SPACE_MARGIN > free) {
                    it.needBytes = need;
                    it.freeBytes = free;
                    fail(it, Problem.NO_SPACE, 0);
                    return;
                }
            }
            if (target == null) target = createTarget(it);
            write(it, c, target, have);
            if (it.cancelled) return;
            if (it.size > 0 && it.durable < it.size) throw new IOException("Connection closed before the end");
            finish(it, target);
        } catch (IOException e) {
            onBroken(it, e);
        } finally {
            if (c != null) c.disconnect();
            it.active.set(false);
            listener.onChanged();
        }
    }

    private void write(Item it, HttpURLConnection c, Uri target, long have) throws IOException {
        long seen = checkpoint.get();
        ParcelFileDescriptor pfd = resolver.openFileDescriptor(target, "rw");
        if (pfd == null) throw new FileNotFoundException("Downloads entry unavailable");
        // The stream owns the descriptor and closes it once, which also tells the system the file changed.
        try (FileOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pfd);
             InputStream in = new BufferedInputStream(c.getInputStream(), BUFFER)) {
            FileChannel ch = out.getChannel();
            ch.truncate(have);
            ch.position(have);
            it.durable = have;
            it.received = have;
            persist(true);
            byte[] buf = new byte[BUFFER];
            long unsynced = 0L;
            long syncedAt = SystemClock.elapsedRealtime();
            try {
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (it.cancelled) return;
                    out.write(buf, 0, n);
                    it.received += n;
                    unsynced += n;
                    long now = SystemClock.elapsedRealtime();
                    long mark = checkpoint.get();
                    if (mark != seen || (unsynced >= SYNC_BYTES && now - syncedAt >= SYNC_EVERY_MS)) {
                        sync(pfd, it);
                        unsynced = 0L;
                        syncedAt = now;
                        seen = mark;
                    }
                }
            } finally {
                // Keep everything that arrived, even when the network broke mid-block.
                if (!it.cancelled) sync(pfd, it);
            }
        }
    }

    private void sync(ParcelFileDescriptor pfd, Item it) throws IOException {
        pfd.getFileDescriptor().sync();
        it.durable = it.received;
        persist(false);
    }

    private void finish(Item it, Uri target) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        try {
            resolver.update(target, v, null, null);
        } catch (RuntimeException e) {
            throw new IOException("Could not publish the file", e);
        }
        if (it.size > 0) it.durable = it.size;
        it.received = it.durable;
        it.state = State.DONE;
        it.problem = Problem.NONE;
        it.finishedMs = System.currentTimeMillis();
        persist(true);
        listener.onFinished(it);
    }

    private void onBroken(Item it, IOException e) {
        if (it.cancelled) return;
        if (isNoSpace(e)) {
            it.needBytes = Math.max(0L, it.size - it.durable);
            it.freeBytes = Math.max(0L, freeBytes());
            fail(it, Problem.NO_SPACE, 0);
            return;
        }
        it.state = State.WAITING;
        if (!networkOk || offline != Offline.NONE) {
            it.problem = Problem.NO_NETWORK;
            it.quickRetries = 0;
        } else if (it.quickRetries < QUICK_RETRIES) {
            it.quickRetries++;
            it.problem = Problem.RETRYING;
            schedule(it, Problem.RETRYING, QUICK_RETRY_S);
        } else {
            waitAndRetry(it, Problem.SERVER_UNREACHABLE, 0, -1);
            return;
        }
        persist(true);
        Log.i(TAG, "Paused " + it.name + ": " + e.getMessage());
    }

    private void waitAndRetry(Item it, Problem problem, int code, int afterS) {
        int delay = afterS > 0 ? afterS : Backoff.delayS(it.attempts);
        it.attempts++;
        it.state = State.WAITING;
        it.problem = problem;
        it.code = code;
        it.retryAtMs = System.currentTimeMillis() + delay * 1000L;
        schedule(it, problem, delay);
        persist(true);
    }

    private void schedule(Item it, Problem problem, long delayS) {
        try {
            timer.schedule(() -> {
                if (it.state == State.WAITING && it.problem == problem) start(it);
            }, delayS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "Retry timer stopped", e);
        }
    }

    private void fail(Item it, Problem problem, int code) {
        it.state = State.FAILED;
        it.problem = problem;
        it.code = code;
        persist(true);
    }

    /** The saved entry of an unfinished download, or null when there is none or it was deleted. */
    private Uri existingTarget(Item it) {
        if (it.uri.isEmpty()) return null;
        Uri u = Uri.parse(it.uri);
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(u, "r")) {
            if (pfd != null) {
                long size = pfd.getStatSize();
                if (size >= 0 && size < it.durable) it.durable = size;
                return u;
            }
        } catch (IOException | RuntimeException e) {
            Log.i(TAG, "The saved part of " + it.name + " is gone; starting again");
        }
        it.uri = "";
        it.durable = 0L;
        it.received = 0L;
        return null;
    }

    private Uri createTarget(Item it) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, it.name);
        // With an extension, the system picks the type from it; without one, ours names the type.
        if (!it.mime.isEmpty() && !it.name.contains(".")) v.put(MediaStore.MediaColumns.MIME_TYPE, it.mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri u;
        try {
            u = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        } catch (RuntimeException e) {
            throw new IOException("Downloads folder unavailable", e);
        }
        if (u == null) throw new IOException("Downloads folder unavailable");
        it.uri = u.toString();
        String actual = displayName(u);
        if (actual != null && !actual.isEmpty()) it.name = actual;
        persist(true);
        return u;
    }

    /** The name the system gave the file; it adds " (1)" when the name was taken. */
    private String displayName(Uri u) {
        String[] columns = {MediaStore.MediaColumns.DISPLAY_NAME};
        try (Cursor cur = queryIncludingPending(u, columns)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (RuntimeException e) {
            Log.d(TAG, "Could not read the saved name", e);
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private Cursor queryIncludingPending(Uri u, String[] columns) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bundle args = new Bundle();
            args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
            return resolver.query(u, columns, args, null);
        }
        return resolver.query(MediaStore.setIncludePending(u), columns, null, null, null);
    }

    private void deleteEntry(String uri) {
        try {
            resolver.delete(Uri.parse(uri), null, null);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not delete " + uri, e);
        }
    }

    private long freeBytes() {
        if (storage == null) return -1L;
        try {
            return storage.getAllocatableBytes(StorageManager.UUID_DEFAULT);
        } catch (IOException | RuntimeException e) {
            return -1L;
        }
    }

    private void persist(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - persistedAtMs < PERSIST_EVERY_MS) return;
        synchronized (saveLock) {
            persistedAtMs = now;
            stores.saveTransfers(items);
        }
    }

    private static void deleteOldParts(File dir) {
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                if (f.getName().endsWith(".part") && !f.delete()) Log.w(TAG, "Could not delete " + f);
            }
        }
        if (dir.isDirectory() && !dir.delete()) Log.d(TAG, "Old download folder not empty");
    }

    private static boolean secure(Uri u) {
        String host = u.getHost();
        return "https".equalsIgnoreCase(u.getScheme()) && host != null && !host.isEmpty();
    }

    private static String header(HttpURLConnection c, String name) {
        String v = c.getHeaderField(name);
        return v == null ? "" : v;
    }

    /** A web page sent for a link that should be a file: a login or "can't scan for viruses" page. */
    static boolean isWebPage(String contentType, URL url) {
        String t = contentType == null ? "" : mimeOnly(contentType).toLowerCase(Locale.ROOT);
        if (!t.equals("text/html") && !t.equals("application/xhtml+xml")) return false;
        String path = url == null ? "" : url.getPath().toLowerCase(Locale.ROOT);
        return !(path.endsWith(".html") || path.endsWith(".htm") || path.endsWith(".xhtml"));
    }

    static boolean isNoSpace(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && (m.contains("ENOSPC") || m.contains("No space left"))) return true;
        }
        return false;
    }

    private static String extensionFor(String mime) {
        if (mime.isEmpty() || mime.endsWith("octet-stream")) return null;
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
    }

    static String mimeOnly(String contentType) {
        int semicolon = contentType.indexOf(';');
        return (semicolon >= 0 ? contentType.substring(0, semicolon) : contentType).trim();
    }

    static String human(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(Locale.ROOT, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format(Locale.ROOT, "%.1f MB", mb);
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }
}

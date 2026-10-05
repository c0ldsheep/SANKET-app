package io.github.c0ldsheep.sanket;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import io.github.c0ldsheep.sanket.core.ByteRanges;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Downloads that survive signal loss.
 *
 * <p>Bytes go straight to a private ".part" file, which is flushed to storage every megabyte and
 * whenever SANKET expects a drop. When the connection breaks, the download waits instead of
 * failing. When the network is back it asks the server for the missing bytes only (an HTTP range
 * request guarded by the ETag or Last-Modified date, so a changed file is never stitched onto an
 * old one). Finished files go to the phone's Downloads folder.
 */
final class Transfers {
    enum State { QUEUED, RUNNING, WAITING, DONE, FAILED }

    /** One download. Its worker thread writes the fields; the screen only reads them. */
    static final class Item {
        final String id;
        final String url;
        final String name;
        final AtomicBoolean active = new AtomicBoolean();
        volatile long size = -1L;
        volatile long done;
        volatile String etag = "";
        volatile String lastModified = "";
        volatile String mime = "";
        volatile State state = State.QUEUED;
        volatile String message = "";
        volatile int retries;
        volatile int resumes;
        volatile boolean cancelled;

        Item(String id, String url, String name) {
            this.id = id;
            this.url = url;
            this.name = name;
        }

        /** Progress in percent, or -1 while the size is unknown. */
        int percent() {
            long total = size;
            return total > 0 ? (int) Math.min(100L, done * 100L / total) : -1;
        }
    }

    interface LatencyListener { void onLatency(double millis); }

    private static final String TAG = "SanketTransfers";
    private static final int QUICK_RETRIES = 3;
    private static final long SYNC_BYTES = 1L << 20;
    private static final int BUFFER = 64 * 1024;

    private final Context ctx;
    private final Stores stores;
    private final LatencyListener latency;
    private final File dir;
    private final List<Item> items = new CopyOnWriteArrayList<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong checkpoint = new AtomicLong();
    private final Object saveLock = new Object();
    private volatile boolean networkOk = true;

    Transfers(Context ctx, Stores stores, LatencyListener latency) {
        this.ctx = ctx;
        this.stores = stores;
        this.latency = latency;
        dir = new File(ctx.getFilesDir(), "downloads");
        if (!dir.isDirectory() && !dir.mkdirs()) Log.w(TAG, "Cannot create " + dir);
        for (Item it : stores.loadTransfers()) {
            if (it.state == State.QUEUED || it.state == State.RUNNING) it.state = State.WAITING;
            if (it.state != State.DONE) it.done = partFile(it).length();
            items.add(it);
        }
    }

    /** Starts downloading an https link. Returns false for anything that is not one. */
    boolean add(String link) {
        Uri uri = Uri.parse(link == null ? "" : link.trim());
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || host.isEmpty()) return false;
        Item it = new Item(UUID.randomUUID().toString(), uri.toString(), fileName(uri));
        it.message = ctx.getString(R.string.transfer_queued);
        items.add(0, it);
        persist();
        start(it);
        return true;
    }

    List<Item> items() { return items; }

    boolean busy() {
        for (Item it : items) {
            if (it.state == State.QUEUED || it.state == State.RUNNING || it.state == State.WAITING) return true;
        }
        return false;
    }

    /** Running downloads flush to storage at their next block. Cheap; called on every warning. */
    void checkpointAll() { checkpoint.incrementAndGet(); }

    void onNetwork(boolean working) {
        boolean back = working && !networkOk;
        networkOk = working;
        if (back) resumeWaiting();
    }

    void resumeWaiting() {
        for (Item it : items) {
            if (it.state == State.WAITING) retry(it);
        }
    }

    void retry(Item it) {
        it.retries = 0;
        start(it);
    }

    void remove(Item it) {
        it.cancelled = true;
        items.remove(it);
        deletePart(it);
        persist();
    }

    void clearAll() {
        for (Item it : items) {
            it.cancelled = true;
            deletePart(it);
        }
        items.clear();
    }

    private void start(Item it) {
        if (it.cancelled || it.state == State.DONE || !it.active.compareAndSet(false, true)) return;
        it.state = State.QUEUED;
        pool.execute(() -> run(it));
    }

    private void run(Item it) {
        File part = partFile(it);
        HttpURLConnection c = null;
        try {
            if (it.cancelled) return;
            long have = part.length();
            it.state = State.RUNNING;
            it.message = have > 0 ? ctx.getString(R.string.transfer_resuming, human(have))
                    : ctx.getString(R.string.transfer_running);
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
            latency.onLatency(SystemClock.elapsedRealtime() - started);
            boolean append;
            if (code == HttpURLConnection.HTTP_PARTIAL && have > 0
                    && ByteRanges.first(c.getHeaderField("Content-Range")) == have) {
                append = true;
                it.resumes++;
            } else if (code == HttpURLConnection.HTTP_OK) {
                append = false;   // first request, or the server cannot resume, or the file changed
                have = 0L;
                String tag = c.getHeaderField("ETag");
                String modified = c.getHeaderField("Last-Modified");
                String type = c.getContentType();
                it.etag = tag == null ? "" : tag;
                it.lastModified = modified == null ? "" : modified;
                it.mime = type == null ? "" : type;
            } else if (code == HttpURLConnection.HTTP_PARTIAL) {
                deletePart(it);
                throw new IOException("Server sent a different range; starting again");
            } else if (code == 416 && it.size > 0 && have >= it.size) {
                publish(it, part);
                return;
            } else {
                fail(it, ctx.getString(R.string.transfer_failed, code));
                return;
            }
            long total = code == HttpURLConnection.HTTP_PARTIAL ? ByteRanges.total(c.getHeaderField("Content-Range"))
                    : c.getContentLengthLong();
            if (total > 0) it.size = total;
            it.done = have;
            persist();
            long seen = checkpoint.get();
            try (InputStream in = new BufferedInputStream(c.getInputStream(), BUFFER);
                 FileOutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[BUFFER];
                long unsynced = 0L;
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (it.cancelled) return;
                    out.write(buf, 0, n);
                    it.done += n;
                    unsynced += n;
                    long now = checkpoint.get();
                    if (unsynced >= SYNC_BYTES || now != seen) {
                        out.flush();
                        out.getFD().sync();
                        unsynced = 0L;
                        seen = now;
                        persist();
                    }
                }
                out.flush();
                out.getFD().sync();
            }
            if (it.size > 0 && it.done < it.size) throw new IOException("Connection closed before the end");
            publish(it, part);
        } catch (IOException e) {
            if (it.cancelled) return;
            it.done = part.length();
            it.state = State.WAITING;
            if (networkOk && it.retries < QUICK_RETRIES) {
                it.retries++;
                it.message = ctx.getString(R.string.transfer_retrying);
                timer.schedule(() -> start(it), 5L, TimeUnit.SECONDS);
            } else {
                it.message = ctx.getString(R.string.transfer_waiting, human(it.done));
            }
            persist();
            Log.i(TAG, "Paused " + it.name + ": " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
            it.active.set(false);
        }
    }

    /** Copies the finished file into the shared Downloads folder. */
    private void publish(Item it, File part) throws IOException {
        ContentResolver resolver = ctx.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, it.name);
        String mime = mimeOnly(it.mime);
        if (!mime.isEmpty()) values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Downloads folder unavailable");
        try (OutputStream out = resolver.openOutputStream(uri); InputStream in = new FileInputStream(part)) {
            if (out == null) throw new IOException("Downloads folder unavailable");
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } catch (IOException e) {
            resolver.delete(uri, null, null);
            throw e;
        }
        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        deletePart(it);
        it.state = State.DONE;
        it.message = it.resumes > 0
                ? ctx.getResources().getQuantityString(R.plurals.transfer_saved_resumed, it.resumes, it.resumes)
                : ctx.getString(R.string.transfer_saved);
        persist();
    }

    private void fail(Item it, String message) {
        it.state = State.FAILED;
        it.message = message;
        persist();
    }

    private void persist() {
        synchronized (saveLock) {
            stores.saveTransfers(items);
        }
    }

    private File partFile(Item it) { return new File(dir, it.id + ".part"); }

    private void deletePart(Item it) {
        File part = partFile(it);
        if (part.exists() && !part.delete()) Log.w(TAG, "Could not delete " + part);
    }

    static String fileName(Uri uri) {
        String last = uri.getLastPathSegment();
        String name = last == null ? "" : last.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "download";
        return name.length() > 80 ? name.substring(name.length() - 80) : name;
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

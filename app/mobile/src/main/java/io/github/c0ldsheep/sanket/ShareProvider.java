package io.github.c0ldsheep.sanket;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lends one file from the app's cache, read only, to the app the rider picks in the share sheet (a test ride as a
 * CSV file). Only files in cache/share can be reached, and the copies there are deleted the next time SANKET opens.
 */
public final class ShareProvider extends ContentProvider {
    static final String AUTHORITY = "io.github.c0ldsheep.sanket.share";
    private static final String TAG = "SanketShare";

    static File dir(Context ctx) {
        File d = new File(ctx.getCacheDir(), "share");
        if (!d.isDirectory() && !d.mkdirs()) Log.w(TAG, "Could not create " + d);
        return d;
    }

    static Uri uri(File f) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(AUTHORITY).appendPath(f.getName())
                .build();
    }

    /** Deletes copies shared earlier: they are plain text, while the kept recording stays encrypted. */
    static void clean(Context ctx) {
        File[] old = dir(ctx).listFiles();
        if (old == null) return;
        for (File f : old) {
            if (!f.delete()) Log.w(TAG, "Could not delete " + f.getName());
        }
    }

    @Override
    public boolean onCreate() { return true; }

    private File file(Uri uri) throws FileNotFoundException {
        Context ctx = getContext();
        String name = uri.getLastPathSegment();
        if (ctx == null || name == null || name.startsWith(".") || !name.equals(new File(name).getName())) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        File f = new File(dir(ctx), name);
        if (!f.isFile()) throw new FileNotFoundException(name);
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("Read only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = file(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection
                : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        MatrixCursor c = new MatrixCursor(cols, 1);
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) { return "text/csv"; }

    @Override
    public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}

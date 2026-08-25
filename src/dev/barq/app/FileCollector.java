package dev.barq.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import dev.barq.IBarqService;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Moves received files out of the daemon and into {@code Downloads/Barq}.
 *
 * <p><b>Why the app does this and not the daemon.</b> {@code barqsharingd} runs as
 * {@code nobody} with no capabilities, because it parses input from any device on the
 * link. It deliberately has no access to shared storage at all. This app has the
 * standing to write there and to tell MediaStore about it, and it only ever handles
 * bytes the daemon has already extracted — it never parses an archive itself.
 *
 * <p>Files arrive as file descriptors over binder rather than through a shared
 * directory, so this app needs no read access to the daemon's storage and cannot reach
 * anything the daemon did not hand over.
 */
final class FileCollector {

    private static final String TAG = "BarqCollect";

    /** Where received files land, matching where Quick Share puts its own. */
    private static final String DEST_DIR = Environment.DIRECTORY_DOWNLOADS + "/Barq";

    private FileCollector() {}

    /** One file that made it to Downloads/Barq. */
    static final class Stored {
        final String name;
        final long bytes;
        /**
         * Where it landed, so the UI can offer to open it.
         *
         * Without this a received file vanished into Downloads with no acknowledgement,
         * which was the worst thing about the old screen: the transfer succeeded and
         * left no trace the user could act on.
         */
        final Uri uri;
        final long receivedAt = System.currentTimeMillis();

        Stored(String name, long bytes, Uri uri) {
            this.name = name;
            this.bytes = bytes;
            this.uri = uri;
        }
    }

    /**
     * Collect everything the daemon is holding.
     *
     * <p>Returns what was actually stored, with the byte count measured during the copy.
     * The size cannot be read from the daemon's inbox instead: that directory is 0700
     * nobody and even a system-uid app gets nothing from it, which is why an earlier
     * version showed every file with no size at all.
     */
    static java.util.List<Stored> collectAll(Context context, IBarqService service) {
        java.util.List<Stored> stored = new java.util.ArrayList<>();
        String[] names;
        try {
            names = service.getReceivedFiles();
        } catch (Exception e) {
            Log.e(TAG, "could not list received files", e);
            return stored;
        }
        for (String name : names) {
            Stored s = collectOne(context, service, name);
            if (s != null) {
                stored.add(s);
            }
        }
        return stored;
    }

    /** @return what was stored, or null if it could not be */
    private static Stored collectOne(Context context, IBarqService service, String name) {
        Uri dest = null;
        try (ParcelFileDescriptor pfd = service.openReceivedFile(name)) {
            if (pfd == null) {
                Log.w(TAG, "daemon returned no descriptor for " + name);
                return null;
            }
            ContentResolver resolver = context.getContentResolver();

            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.RELATIVE_PATH, DEST_DIR);
            // IS_PENDING hides the row until the bytes are actually written, so nothing
            // ever sees a half-copied file in the Files app.
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            dest = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (dest == null) {
                Log.e(TAG, "MediaStore refused an entry for " + name);
                return null;
            }

            long copied = 0;
            try (InputStream in = new FileInputStream(pfd.getFileDescriptor());
                 OutputStream out = resolver.openOutputStream(dest)) {
                if (out == null) {
                    Log.e(TAG, "no output stream for " + name);
                    return null;
                }
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    copied += n;
                }
            }

            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(dest, values, null, null);

            // Only now is it safe to drop the daemon's copy: if anything above failed,
            // the file is still in the inbox and the next attempt can retry it.
            service.deleteReceivedFile(name);
            Log.i(TAG, "stored " + name + " (" + copied + " bytes) in " + DEST_DIR);
            return new Stored(name, copied, dest);

        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "could not store " + name, e);
            if (dest != null) {
                // Leave no pending half-written row behind.
                try {
                    context.getContentResolver().delete(dest, null, null);
                } catch (RuntimeException ignored) {
                    // Nothing useful to do; the entry stays pending and hidden.
                }
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "binder failure collecting " + name, e);
            return null;
        }
    }
}

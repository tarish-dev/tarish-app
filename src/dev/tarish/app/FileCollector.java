package dev.tarish.app;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import dev.tarish.ITarishService;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Moves received files out of the daemon and into {@code Downloads/Tarish}.
 *
 * <p><b>Why the app does this and not the daemon.</b> {@code tarishsharingd} runs as
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

    private static final String TAG = "TarishCollect";

    /** Where received files land, matching where Quick Share puts its own. */
    private static final String DEST_DIR = Environment.DIRECTORY_DOWNLOADS + "/Tarish";

    private FileCollector() {}

    /** One file that made it to Downloads/Tarish. */
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
    static java.util.List<Stored> collectAll(Context context, ITarishService service) {
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
                // An Apple note is not readable on Android. Unpack it into things that
                // are, and keep the original -- it is the faithful artefact and the only
                // thing that round-trips back to an Apple device.
                stored.addAll(unpackAppleNote(context, s));
            }
        }
        return stored;
    }

    /**
     * Turn a received `.notesairdropdocument` into usable files beside it.
     *
     * <p>A note arrives as an Apple Notes protobuf: text notes carry one UTF-8 string,
     * audio notes carry the same text plus complete M4A recordings. Android opens neither.
     * This writes the text as `.txt` and each recording as `.m4a`, so what the sender
     * actually shared is something the receiver can read and play.
     *
     * <p>Failure here is never fatal: the original file is already stored, so the worst
     * case is the user gets what they would have got anyway.
     */
    private static java.util.List<Stored> unpackAppleNote(Context context, Stored original) {
        java.util.List<Stored> extra = new java.util.ArrayList<>();
        if (!AppleNote.looksLikeOne(original.name)) {
            return extra;
        }
        byte[] doc;
        try (InputStream in = context.getContentResolver().openInputStream(original.uri)) {
            if (in == null) {
                return extra;
            }
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[64 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            doc = buf.toByteArray();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "could not re-read " + original.name + " to unpack it", e);
            return extra;
        }

        AppleNote note = AppleNote.parse(doc);
        String base = original.name.substring(
                0, original.name.length() - AppleNote.EXTENSION.length());

        if (note.text != null) {
            // A BOM, deliberately. Android's default charset is UTF-8 so most apps are
            // fine without one, but text editors that guess an 8-bit codepage turn
            // non-ASCII into mojibake -- which is exactly what the operator hit sending a
            // note Android to Android. EF BB BF is an unambiguous "this is UTF-8" that
            // guessing apps honour. Only ever on .txt: it breaks shell scripts and several
            // structured-format parsers.
            byte[] utf8 = note.text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] withBom = new byte[utf8.length + 3];
            withBom[0] = (byte) 0xEF;
            withBom[1] = (byte) 0xBB;
            withBom[2] = (byte) 0xBF;
            System.arraycopy(utf8, 0, withBom, 3, utf8.length);
            Stored t = storeBytes(context, base + ".txt", withBom);
            if (t != null) {
                extra.add(t);
            }
        }

        for (int i = 0; i < note.recordings.size(); i++) {
            String name = note.recordings.size() == 1
                    ? base + ".m4a"
                    : base + " (" + (i + 1) + ").m4a";
            Stored a = storeBytes(context, name, note.recordings.get(i));
            if (a != null) {
                extra.add(a);
            }
        }

        if (!extra.isEmpty()) {
            Log.i(TAG, "unpacked " + original.name + " into " + extra.size() + " usable file(s)");
        }
        return extra;
    }

    /** Store a byte array in Downloads/Tarish, with the same stale-row handling as a copy. */
    private static Stored storeBytes(Context context, String name, byte[] bytes) {
        ContentResolver resolver = context.getContentResolver();
        dropStaleRows(resolver, name);

        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.RELATIVE_PATH, DEST_DIR);
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri dest = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (dest == null) {
            Log.e(TAG, "MediaStore refused an entry for " + name);
            return null;
        }
        try {
            try (OutputStream out = resolver.openOutputStream(dest)) {
                if (out == null) {
                    throw new IOException("no output stream for " + name);
                }
                out.write(bytes);
            }
            String finalName = publish(resolver, dest, name);
            if (finalName == null) {
                throw new IOException("MediaStore would not publish " + name);
            }
            Log.i(TAG, "stored " + finalName + " (" + bytes.length + " bytes) in " + DEST_DIR);
            return new Stored(finalName, bytes.length, dest);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "could not store " + name, e);
            try {
                resolver.delete(dest, null, null);
            } catch (RuntimeException ignored) {
                // Nothing useful to do; the entry stays pending and hidden.
            }
            return null;
        }
    }

    /** @return what was stored, or null if it could not be */
    private static Stored collectOne(Context context, ITarishService service, String name) {
        Uri dest = null;
        try (ParcelFileDescriptor pfd = service.openReceivedFile(name)) {
            if (pfd == null) {
                Log.w(TAG, "daemon returned no descriptor for " + name);
                return null;
            }
            ContentResolver resolver = context.getContentResolver();
            dropStaleRows(resolver, name);

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

            String stored = publish(resolver, dest, name);
            if (stored == null) {
                // Thrown rather than returned so the pending row below is cleaned up and
                // the daemon keeps its copy for the next attempt.
                throw new IOException("MediaStore would not publish " + name
                        + " under any name");
            }

            // Only now is it safe to drop the daemon's copy: if anything above failed,
            // the file is still in the inbox and the next attempt can retry it. This
            // takes the name the daemon knows the file by, NOT the name it was stored
            // under -- publish() renames around a collision and the two can differ.
            service.deleteReceivedFile(name);
            Log.i(TAG, "stored " + stored + " (" + copied + " bytes) in " + DEST_DIR);
            return new Stored(stored, copied, dest);

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

    /**
     * Removes rows that point at a file which is no longer there.
     *
     * <p>MediaStore keeps the row and the file in separate places and they can
     * disagree: delete a received file from outside the app and the row survives,
     * still owning the path. The next file of the same name then inserts cleanly,
     * copies every byte, and fails at the very last step -- clearing IS_PENDING
     * renames the file onto the path the dead row holds, and the provider answers
     *
     * <pre>SQLiteConstraintException: UNIQUE constraint failed: files._data</pre>
     *
     * which used to be caught, logged and dropped. The transfer had succeeded and the
     * file simply never appeared.
     *
     * <p>MediaStore's own de-duplication does not cover this, because it looks at the
     * filesystem: it finds no file, so it does not rename, and the collision is with a
     * row rather than with a file.
     *
     * <p>This app holds no storage permission, so the query returns only rows it owns
     * itself -- it cannot see, let alone delete, something another app put there.
     */
    private static void dropStaleRows(ContentResolver resolver, String name) {
        String[] projection = { MediaStore.Downloads._ID };
        String selection = MediaStore.Downloads.RELATIVE_PATH + "=? AND "
                + MediaStore.Downloads.DISPLAY_NAME + "=?";
        // MediaStore normalises RELATIVE_PATH with a trailing slash; without it the
        // selection matches nothing and this quietly does no work at all.
        String[] args = { DEST_DIR + "/", name };

        try (Cursor c = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection, selection, args, null)) {
            if (c == null) {
                return;
            }
            while (c.moveToNext()) {
                Uri row = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0));
                // OPENING it is the existence check -- with no storage permission the
                // app cannot stat the path itself.
                try (ParcelFileDescriptor open = resolver.openFileDescriptor(row, "r")) {
                    if (open == null) {
                        // No descriptor and no exception. Treat the row as live rather
                        // than deleting one we cannot prove is stale.
                        continue;
                    }
                    // The file is really there, so this is an ordinary duplicate name.
                    // Leave it alone: MediaStore will store ours as "name (1)" the way
                    // it does for any other download.
                } catch (FileNotFoundException gone) {
                    // Opening it is the only existence check available -- with no
                    // storage permission the app cannot stat the path itself.
                    Log.i(TAG, "dropping a stale MediaStore row for " + name);
                    try {
                        resolver.delete(row, null, null);
                    } catch (RuntimeException refused) {
                        Log.w(TAG, "could not drop the stale row for " + name, refused);
                    }
                } catch (IOException closing) {
                    // close() failing says nothing about whether the row is stale.
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "could not check MediaStore for stale rows for " + name, e);
        }
    }

    /**
     * Clears IS_PENDING, which is what actually moves the file onto its final path.
     *
     * @return the name it was stored under -- not always the name asked for -- or null
     *     if no name worked.
     */
    private static String publish(ContentResolver resolver, Uri dest, String name) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        try {
            resolver.update(dest, values, null, null);
            return name;
        } catch (RuntimeException taken) {
            // A row still holds this path and dropStaleRows() could not remove it --
            // it belongs to another app. Renaming is the only way through, and a file
            // under a slightly different name beats a file the user never sees.
            Log.w(TAG, "the name " + name + " is spoken for; renaming", taken);
        }

        for (int i = 1; i <= 32; i++) {
            String alt = suffixed(name, i);
            values.clear();
            values.put(MediaStore.Downloads.DISPLAY_NAME, alt);
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            try {
                resolver.update(dest, values, null, null);
                return alt;
            } catch (RuntimeException alsoTaken) {
                // Try the next one.
            }
        }
        return null;
    }

    /** {@code report.pdf} -> {@code report (2).pdf}, matching how MediaStore renames. */
    private static String suffixed(String name, int n) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return name + " (" + n + ")";
        }
        return name.substring(0, dot) + " (" + n + ")" + name.substring(dot);
    }
}

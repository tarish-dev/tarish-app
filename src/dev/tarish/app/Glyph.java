package dev.tarish.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.widget.ImageView;

/**
 * The icon set — the brand kit's, not ours.
 *
 * <p>This has now been three things. It started as Unicode characters — ⚡ ⇄ ⤓ — which
 * betrayed the whole screen: the bolt rendered as a yellow emoji while the arrows came
 * out as hairlines, so nothing shared a weight or a colour. The font decides how those
 * look, not us. It then became a set of hand-drawn canvas paths, whose stated reason was
 * that "real vector drawables would need a resource directory this app deliberately does
 * not have" — true when written, and no longer true: the app has res/ for the brand
 * colours and the mark.
 *
 * <p>So these are now the kit's own icons, imported unchanged: 24px grid, 2px stroke,
 * round caps and joins, the same geometry as the mark's waves. Approximating that by hand
 * cannot land on it, and every icon that misses is visible next to the ones that don't.
 *
 * <p>The kit's icons are stroked with {@code currentColor}, which a VectorDrawable has no
 * equivalent for. The drawables are stroked white and the colour arrives as an image tint,
 * which is a SRC_IN filter over the whole drawable — so one drawable serves every role
 * (accent, muted, on-accent, error) and both themes, exactly as currentColor would.
 *
 * <p>The API is deliberately unchanged from the canvas version — {@code new Glyph(ctx,
 * Kind.CHECK, colour)} and {@code setColor()} — so no call site had to move.
 */
final class Glyph extends ImageView {

    /**
     * DEVICE and PHONE are the peer tiles. There is no laptop icon in the kit and that is
     * not an omission: devices.svg is the general "some other machine" mark, and a laptop
     * drawn specially would claim knowledge we do not have — a peer advertises a model
     * string or nothing at all.
     */
    enum Kind {
        SWAP, DOWNLOAD, UPLOAD, DEVICE, PHONE, CHECK, GEAR,
        // File-type marks for the transfer/offer cards.
        PHOTO, VIDEO, AUDIO, DOC, ARCHIVE, FILE
    }

    /**
     * The type mark for a file, by extension alone.
     *
     * <p>The name is all we have on the receive side until the file lands — a peer offers
     * names, not MIME types — so the extension is the honest source. A batch of mixed
     * types, or a name with no extension, gets the general {@link Kind#FILE} rather than a
     * guess dressed as knowledge.
     */
    static Kind kindForName(String name) {
        if (name == null) {
            return Kind.FILE;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return Kind.FILE;
        }
        String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        switch (ext) {
            case "jpg": case "jpeg": case "png": case "gif": case "webp":
            case "heic": case "heif": case "bmp": case "tif": case "tiff": case "svg":
                return Kind.PHOTO;
            case "mp4": case "mov": case "mkv": case "webm": case "avi":
            case "m4v": case "3gp": case "hevc":
                return Kind.VIDEO;
            case "mp3": case "wav": case "flac": case "aac": case "ogg":
            case "m4a": case "opus": case "aiff":
                return Kind.AUDIO;
            case "pdf": case "doc": case "docx": case "txt": case "rtf": case "md":
            case "ppt": case "pptx": case "xls": case "xlsx": case "csv": case "pages":
                return Kind.DOC;
            case "zip": case "tar": case "gz": case "7z": case "rar":
            case "bz2": case "xz": case "tgz":
                return Kind.ARCHIVE;
            default:
                return Kind.FILE;
        }
    }

    /** One mark for a set: the shared type when they all agree, otherwise {@link Kind#FILE}. */
    static Kind kindForNames(String[] names) {
        if (names == null || names.length == 0) {
            return Kind.FILE;
        }
        Kind first = kindForName(names[0]);
        for (int i = 1; i < names.length; i++) {
            if (kindForName(names[i]) != first) {
                return Kind.FILE;
            }
        }
        return first;
    }

    Glyph(Context c, Kind kind, int color) {
        super(c);
        setImageResource(drawableFor(kind));
        setScaleType(ScaleType.FIT_CENTER);
        setColor(color);
    }

    void setColor(int color) {
        setImageTintList(ColorStateList.valueOf(color));
    }

    private static int drawableFor(Kind kind) {
        switch (kind) {
            case SWAP: return R.drawable.ic_transfer;
            case DOWNLOAD: return R.drawable.ic_download;
            case UPLOAD: return R.drawable.ic_upload;
            case DEVICE: return R.drawable.ic_devices;
            case PHONE: return R.drawable.ic_phone;
            case CHECK: return R.drawable.ic_check;
            case GEAR: return R.drawable.ic_settings;
            case PHOTO: return R.drawable.ic_photo;
            case VIDEO: return R.drawable.ic_video;
            case AUDIO: return R.drawable.ic_audio;
            case DOC: return R.drawable.ic_doc;
            case ARCHIVE: return R.drawable.ic_archive;
            case FILE: return R.drawable.ic_file;
        }
        throw new IllegalArgumentException("no drawable for " + kind);
    }
}

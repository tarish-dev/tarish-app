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
    enum Kind { SWAP, DOWNLOAD, UPLOAD, DEVICE, PHONE, CHECK, GEAR }

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
        }
        throw new IllegalArgumentException("no drawable for " + kind);
    }
}

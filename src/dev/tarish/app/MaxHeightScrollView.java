package dev.tarish.app;

import android.content.Context;
import android.widget.ScrollView;

/**
 * A ScrollView that grows with its content up to a cap, then scrolls within it.
 *
 * <p>The docked "recent" panel at the bottom of each screen: it should hug a short list
 * but never eat the screen when the list is long. A plain ScrollView with WRAP_CONTENT
 * grows without bound; a fixed height wastes space when there is little to show. This caps
 * the measured height and lets the rest be scrolled.
 */
final class MaxHeightScrollView extends ScrollView {

    private final int maxHeightPx;

    MaxHeightScrollView(Context c, int maxHeightPx) {
        super(c);
        this.maxHeightPx = maxHeightPx;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int capped = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST);
        super.onMeasure(widthSpec, capped);
    }
}

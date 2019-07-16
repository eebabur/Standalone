import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import android.support.v7.widget.RecyclerView
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.min

class BeelineLayoutManager : RecyclerView.LayoutManager() {

    enum class Gravity {
        LEFT, RIGHT
    }

    data class State(val anchorPosition: Int, val anchorOffset: Int) : Parcelable {

        constructor(parcel: Parcel) : this(parcel.readInt(), parcel.readInt())

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeInt(anchorPosition)
            dest.writeInt(anchorOffset)
        }

        override fun describeContents(): Int = 0

        companion object CREATOR : Parcelable.Creator<State> {
            override fun createFromParcel(parcel: Parcel): State = State(parcel)
            override fun newArray(size: Int): Array<State?> = arrayOfNulls(size)
        }
    }

    class LayoutParams : RecyclerView.LayoutParams {
        constructor(width: Int, height: Int) : super(width, height)
        constructor(source: ViewGroup.MarginLayoutParams) : super(source)

        var spanSize: Int = 1
        var zIndex: Float = 1f
        var isSolid: Boolean = true
        var verticalOverlay: Float = 0f
        var gravity: Gravity = Gravity.LEFT
        val verticalMargin: Int
            get() = topMargin + bottomMargin
    }

    interface ConfigLookup {
        fun getSpanSize(position: Int): Int
        fun getZIndex(position: Int): Float
        fun isSolid(position: Int): Boolean
        fun getVerticalOverlay(position: Int): Float
        fun getGravity(position: Int): Gravity
    }

    var configLookup: ConfigLookup? = null

    private var anchorPosition = 0
    private var anchorOffset = 0

    private val parentTop: Int
        get() = paddingTop

    private val parentBottom: Int
        get() = height - paddingBottom

    private val parentLeft: Int
        get() = paddingLeft

    private val parentRight: Int
        get() = width - paddingRight

    private val parentMiddle: Int
        get() = width / 2

    private val parentWidth: Int
        get() = parentRight - parentLeft

    private val columnWidth: Int
        get() = parentWidth / 2

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams =
        LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(lp: ViewGroup.LayoutParams): RecyclerView.LayoutParams {
        val recyclerViewLayoutParams = super.generateLayoutParams(lp)
        return LayoutParams(recyclerViewLayoutParams)
    }

    override fun generateLayoutParams(c: Context, attrs: AttributeSet): RecyclerView.LayoutParams {
        val recyclerViewLayoutParams = super.generateLayoutParams(c, attrs)
        return LayoutParams(recyclerViewLayoutParams)
    }

    override fun onSaveInstanceState(): Parcelable = State(anchorPosition, anchorOffset)

    override fun onRestoreInstanceState(state: Parcelable?) {
        (state as? State)?.let {
            anchorPosition = state.anchorPosition
            anchorOffset = state.anchorOffset
        }
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        detachAndScrapAttachedViews(recycler)

        if (state.itemCount <= 0) return
        fillBottom(recycler, state.itemCount)
    }

    override fun scrollToPosition(position: Int) {
        anchorPosition = position
        anchorOffset = 0
        requestLayout()
    }

    override fun canScrollVertically(): Boolean = true

    override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int =
        when {
            childCount == 0 -> 0
            dy < 0 -> {
                val availableTop = if (clipToPadding) parentTop else 0
                var scrolled = 0
                while (scrolled > dy) {
                    val firstChild = getChildAt(0)!!
                    val firstChildTop = getDecoratedTop(firstChild) - firstChild.layoutParams().topMargin
                    val hangingTop = max(0, availableTop - firstChildTop)
                    val scrollBy = min(hangingTop, scrolled - dy)
                    offsetChildrenVerticallyBy(-scrollBy)
                    scrolled -= scrollBy
                    if (anchorPosition == 0) break
                    fillTop(recycler)
                }
                scrolled
            }
            dy > 0 -> {
                val availableBottom = if (clipToPadding) parentBottom else height
                var scrolled = 0
                while (scrolled < dy) {
                    val lastChild = getChildAt(childCount - 1)!!
                    val lastChildPosition = getPosition(lastChild)
                    val layoutParams = lastChild.layoutParams()
                    val lastChildBottom = getDecoratedBottom(lastChild) + layoutParams.bottomMargin
                    val hangingBottom = max(0, lastChildBottom - availableBottom)
                    val scrollBy = min(hangingBottom, dy - scrolled)
                    offsetChildrenVerticallyBy(scrollBy)
                    scrolled += scrollBy
                    if (lastChildPosition == state.itemCount - 1) break
                    fillBottom(recycler, state.itemCount)
                }
                scrolled
            }
            else -> 0
        }
            .also {
                recycleViewsOutOfBounds(recycler)
                updateAnchorOffset()
            }

    fun lastLaidOutViewPosition(): Int =
        when {
            childCount <= 0 -> 0
            else -> {
                val view = getChildAt(childCount - 1)!!
                getPosition(view)
            }
        }

    private fun recycleViewsOutOfBounds(recycler: RecyclerView.Recycler) {

        if (childCount == 0) return
        val childCount = childCount

        var firstVisibleChild = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)!!
            val layoutParams = child.layoutParams()
            val top = if (clipToPadding) parentTop else 0
            if (getDecoratedBottom(child) + layoutParams.bottomMargin < top) {
                firstVisibleChild++
            } else {
                break
            }
        }

        var lastVisibleChild = firstVisibleChild
        for (i in lastVisibleChild until childCount) {
            val child = getChildAt(i)!!
            val layoutParams = child.layoutParams()
            if (getDecoratedTop(child) - layoutParams.topMargin <= if (clipToPadding) parentBottom else height) {
                lastVisibleChild++
            } else {
                lastVisibleChild--
                break
            }
        }

        for (i in childCount - 1 downTo lastVisibleChild + 1) removeAndRecycleViewAt(i, recycler)
        for (i in firstVisibleChild - 1 downTo 0) removeAndRecycleViewAt(i, recycler)
        anchorPosition += firstVisibleChild
    }

    private fun fillBottom(recycler: RecyclerView.Recycler, adapterItemCount: Int) {

        var hardTop: Int
        var softTop: Int

        var startPosition: Int

        if (childCount > 0) {
            val lastChild = getChildAt(childCount - 1)!!
            val lastChildPosition = getPosition(lastChild)
            startPosition = lastChildPosition + 1
            val lp = lastChild.layoutParams()

            hardTop =
                if (lp.isSolid) {
                    getDecoratedBottom(lastChild) + lp.bottomMargin - ((lastChild.measuredHeight + lp.verticalMargin) * lp.verticalOverlay).toInt()
                } else {
                    getDecoratedTop(lastChild)
                }
            softTop = getDecoratedBottom(lastChild) + lp.bottomMargin
        } else {
            hardTop = parentTop + if (anchorPosition < adapterItemCount) anchorOffset else 0
            softTop = parentTop + if (anchorPosition < adapterItemCount) anchorOffset else 0

            startPosition = if (anchorPosition < adapterItemCount) anchorPosition else 0
        }

        val availableBottom = if (clipToPadding) parentBottom else height

        for (i in startPosition until adapterItemCount) {

            if (hardTop > availableBottom) break

            val view = recycler.getViewForPosition(i)
            addView(view)
            view.setBeelineLayoutParams(i)
            view.measure()
            val lp = view.layoutParams()
            if (lp.isSolid) {
                val top = max(
                    hardTop,
                    softTop - ((view.measuredHeight + lp.verticalMargin) * lp.verticalOverlay).toInt()
                )
                val bottom = top + view.measuredHeight + lp.verticalMargin
                layoutView(view, top, bottom)
                hardTop = bottom - ((view.measuredHeight + lp.verticalMargin) * lp.verticalOverlay).toInt()
                softTop = bottom
            } else {
                val top = hardTop
                val bottom = top + view.measuredHeight + lp.verticalMargin
                layoutView(view, top, bottom)
                softTop = bottom
            }
        }
    }

    private fun fillTop(recycler: RecyclerView.Recycler) {
        if (childCount == 0) return

        val firstChild = getChildAt(0)!!
        val firstChildPosition = getPosition(firstChild)
        if (firstChildPosition == 0) return
        val lp = firstChild.layoutParams()
        var hardBottom =
            if (lp.isSolid) {
                getDecoratedTop(firstChild) - lp.topMargin + ((firstChild.measuredHeight + lp.verticalMargin) * lp.verticalOverlay).toInt()
            } else {
                getDecoratedBottom(firstChild)
            }
        var softBottom = getDecoratedTop(firstChild) - lp.topMargin
        val availableTop = if (clipToPadding) parentTop else 0

        for (i in firstChildPosition - 1 downTo 0) {

            if (hardBottom < availableTop) break

            val view = recycler.getViewForPosition(i)
            anchorPosition--
            addView(view, 0)
            view.setBeelineLayoutParams(i)
            view.measure()
            val lp = view.layoutParams()
            if (lp.isSolid) {
                val bottom = min(
                    hardBottom,
                    softBottom + ((view.measuredHeight + lp.verticalMargin) * lp.verticalOverlay).toInt()
                )
                val top = bottom - view.measuredHeight - lp.verticalMargin
                layoutView(view, top, bottom)
                hardBottom = top + ((view.measuredHeight + lp.verticalMargin) * lp.verticalOverlay).toInt()
                softBottom = top
            } else {
                val bottom = hardBottom
                val top = bottom - view.measuredHeight - lp.verticalMargin
                layoutView(view, top, bottom)
                softBottom = top
            }
        }
    }

    private fun layoutView(view: View, top: Int, bottom: Int) {
        view.translationZ = view.layoutParams().zIndex
        val (left, right) = when (view.layoutParams().gravity) {
            Gravity.LEFT -> parentLeft to (if (view.layoutParams().spanSize == 1) parentMiddle else parentRight)
            Gravity.RIGHT -> (if (view.layoutParams().spanSize == 1) parentMiddle else parentLeft) to parentRight
        }
        layoutDecoratedWithMargins(view, left, top, right, bottom)
    }

    private fun updateAnchorOffset() {
        anchorOffset =
            if (childCount > 0) {
                val view = getChildAt(0)!!
                getDecoratedTop(view) - view.layoutParams().topMargin - parentTop
            } else {
                0
            }
    }

    private fun offsetChildrenVerticallyBy(dy: Int) {
        for (i in 0 until childCount) {
            val view = getChildAt(i)!!
            view.scrollVerticallyBy(dy)
        }
    }

    private fun View.scrollVerticallyBy(dy: Int) {
        offsetTopAndBottom(-dy)
    }

    private fun View.layoutParams(): LayoutParams =
        layoutParams as LayoutParams

    private fun View.measure() {
        val widthUsed = if (layoutParams().spanSize == 1) columnWidth else 0
        measureChildWithMargins(this, widthUsed, 0)
    }

    private fun View.setBeelineLayoutParams(position: Int) {
        configLookup?.let {
            layoutParams().apply {
                spanSize = it.getSpanSize(position)
                zIndex = it.getZIndex(position)
                isSolid = it.isSolid(position)
                verticalOverlay = it.getVerticalOverlay(position)
                gravity = it.getGravity(position)
            }
        }
    }
}

/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.commit451.translationviewdraghelper

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.Interpolator
import androidx.core.view.MotionEventCompat
import androidx.core.view.VelocityTrackerCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.ScrollerCompat
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Modified version of ViewDragHelper which accounts for if views
 * have been translated using [View.setX] [View.setY] or
 * [View.setTranslationX] and [View.setTranslationY]
 */
@Suppress("MemberVisibilityCanBePrivate", "unused")
open class TranslationViewDragHelper
/**
 * Apps should use ViewDragHelper.create() to get a new instance.
 * This will allow VDH to use internal compatibility implementations for different
 * platform versions.
 *
 * @param context Context to initialize config-dependent params from
 * @param parentView Parent view to monitor
 * @param callback the callback
 */
private constructor(
    context: Context,
    private val parentView: ViewGroup,
    private val callback: Callback
) {

    companion object {
        private const val TAG = "TransViewDragHelper"

        /**
         * A null/invalid pointer ID.
         */
        const val INVALID_POINTER = -1

        /**
         * A view is not currently being dragged or animating as a result of a fling/snap.
         */
        const val STATE_IDLE = 0

        /**
         * A view is currently being dragged. The position is currently changing as a result
         * of user input or simulated user input.
         */
        const val STATE_DRAGGING = 1

        /**
         * A view is currently settling into place as a result of a fling or
         * predefined non-interactive motion.
         */
        const val STATE_SETTLING = 2

        /**
         * Edge flag indicating that the left edge should be affected.
         */
        const val EDGE_LEFT = 1 shl 0

        /**
         * Edge flag indicating that the right edge should be affected.
         */
        const val EDGE_RIGHT = 1 shl 1

        /**
         * Edge flag indicating that the top edge should be affected.
         */
        const val EDGE_TOP = 1 shl 2

        /**
         * Edge flag indicating that the bottom edge should be affected.
         */
        const val EDGE_BOTTOM = 1 shl 3

        /**
         * Edge flag set indicating all edges should be affected.
         */
        const val EDGE_ALL = EDGE_LEFT or EDGE_TOP or EDGE_RIGHT or EDGE_BOTTOM

        /**
         * Indicates that a check should occur along the horizontal axis
         */
        const val DIRECTION_HORIZONTAL = 1 shl 0

        /**
         * Indicates that a check should occur along the vertical axis
         */
        const val DIRECTION_VERTICAL = 1 shl 1

        /**
         * Indicates that a check should occur along all axes
         */
        const val DIRECTION_ALL = DIRECTION_HORIZONTAL or DIRECTION_VERTICAL

        private const val EDGE_SIZE = 20 // dp

        private const val BASE_SETTLE_DURATION = 256 // ms
        private const val MAX_SETTLE_DURATION = 600 // ms

        /**
         * Interpolator defining the animation curve for scroller
         */
        private val interpolator = Interpolator { t ->
            var value = t
            value -= 1.0f
            value * value * value * value * value + 1.0f
        }

        /**
         * Factory method to create a new ViewDragHelper.
         *
         * @param forParent Parent view to monitor
         * @param cb Callback to provide information and receive events
         * @return a new ViewDragHelper instance
         */
        fun create(forParent: ViewGroup, cb: Callback): TranslationViewDragHelper {
            return TranslationViewDragHelper(forParent.context, forParent, cb)
        }

        /**
         * Factory method to create a new ViewDragHelper.
         *
         * @param forParent Parent view to monitor
         * @param sensitivity Multiplier for how sensitive the helper should be about detecting
         * the start of a drag. Larger values are more sensitive. 1.0f is normal.
         * @param cb Callback to provide information and receive events
         * @return a new ViewDragHelper instance
         */
        fun create(
            forParent: ViewGroup,
            sensitivity: Float,
            cb: Callback
        ): TranslationViewDragHelper {
            val helper = create(forParent, cb)
            helper.touchSlop = (helper.touchSlop * (1 / sensitivity)).toInt()
            return helper
        }
    }

    // Current drag state; idle, dragging or settling
    /**
     * Retrieve the current drag state of this helper. This will return one of
     * [.STATE_IDLE], [.STATE_DRAGGING] or [.STATE_SETTLING].
     * @return The current drag state
     */
    var viewDragState: Int = 0
        private set

    // Distance to travel before a drag may begin
    /**
     * @return The minimum distance in pixels that the user must travel to initiate a drag
     */
    var touchSlop: Int = 0
        private set

    // Last known position/pointer tracking
    /**
     * @return The ID of the pointer currently dragging the captured view,
     * or [.INVALID_POINTER].
     */
    var activePointerId = INVALID_POINTER
        private set
    private var initialMotionX: FloatArray? = null
    private var initialMotionY: FloatArray? = null
    private var lastMotionX: FloatArray? = null
    private var lastMotionY: FloatArray? = null
    private var initialEdgesTouched: IntArray? = null
    private var edgeDragsInProgress: IntArray? = null
    private var edgeDragsLocked: IntArray? = null
    private var pointersDown: Int = 0

    private var velocityTracker: VelocityTracker? = null
    private val maxVelocity: Float

    /**
     * Return the currently configured minimum velocity. Any flings with a magnitude less
     * than this value in pixels per second. Callback methods accepting a velocity will receive
     * zero as a velocity value if the real detected velocity was below this threshold.
     *
     * Set the minimum velocity that will be detected as having a magnitude greater than zero
     * in pixels per second. Callback methods accepting a velocity will be clamped appropriately.
     */
    var minVelocity: Float = 0.toFloat()

    /**
     * Return the size of an edge. This is the range in pixels along the edges of this view
     * that will actively detect edge touches or drags if edge tracking is enabled.
     *
     * @return The size of an edge in pixels
     * @see .setEdgeTrackingEnabled
     */
    val edgeSize: Int

    private var trackingEdges: Int = 0

    private val scroller: ScrollerCompat

    /**
     * @return The currently captured view, or null if no view has been captured.
     */
    var capturedView: View? = null
        private set

    private var releaseInProgress: Boolean = false

    private val setIdleRunnable = Runnable { setDragState(STATE_IDLE) }

    /**
     * A Callback is used as a communication channel with the ViewDragHelper back to the
     * parent view using it. `on*`methods are invoked on siginficant events and several
     * accessor methods are expected to provide the ViewDragHelper with more information
     * about the state of the parent view upon request. The callback also makes decisions
     * governing the range and draggability of child views.
     */
    @Suppress("UNUSED_PARAMETER")
    abstract class Callback {
        /**
         * Called when the drag state changes. See the `STATE_*` constants
         * for more information.
         *
         * @param state The new drag state
         *
         * @see .STATE_IDLE
         *
         * @see .STATE_DRAGGING
         *
         * @see .STATE_SETTLING
         */
        open fun onViewDragStateChanged(state: Int) {}

        /**
         * Called when the captured view's position changes as the result of a drag or settle.
         *
         * @param changedView View whose position changed
         * @param left New X coordinate of the left edge of the view
         * @param top New Y coordinate of the top edge of the view
         * @param dx Change in X position from the last call
         * @param dy Change in Y position from the last call
         */
        open fun onViewPositionChanged(changedView: View?, left: Int, top: Int, dx: Int, dy: Int) {}

        /**
         * Called when a child view is captured for dragging or settling. The ID of the pointer
         * currently dragging the captured view is supplied. If activePointerId is
         * identified as [.INVALID_POINTER] the capture is programmatic instead of
         * pointer-initiated.
         *
         * @param capturedChild Child view that was captured
         * @param activePointerId Pointer id tracking the child capture
         */
        open fun onViewCaptured(capturedChild: View, activePointerId: Int) {}

        /**
         * Called when the child view is no longer being actively dragged.
         * The fling velocity is also supplied, if relevant. The velocity values may
         * be clamped to system minimums or maximums.
         *
         *
         * Calling code may decide to fling or otherwise release the view to let it
         * settle into place. It should do so using [.settleCapturedViewAt]
         * or [.flingCapturedView]. If the Callback invokes
         * one of these methods, the ViewDragHelper will enter [.STATE_SETTLING]
         * and the view capture will not fully end until it comes to a complete stop.
         * If neither of these methods is invoked before `onViewReleased` returns,
         * the view will stop in place and the ViewDragHelper will return to
         * [.STATE_IDLE].
         *
         * @param releasedChild The captured child view now being released
         * @param xvel X velocity of the pointer as it left the screen in pixels per second.
         * @param yvel Y velocity of the pointer as it left the screen in pixels per second.
         */
        open fun onViewReleased(releasedChild: View?, xvel: Float, yvel: Float) {}

        /**
         * Called when one of the subscribed edges in the parent view has been touched
         * by the user while no child view is currently captured.
         *
         * @param edgeFlags A combination of edge flags describing the edge(s) currently touched
         * @param pointerId ID of the pointer touching the described edge(s)
         * @see .EDGE_LEFT
         *
         * @see .EDGE_TOP
         *
         * @see .EDGE_RIGHT
         *
         * @see .EDGE_BOTTOM
         */
        open fun onEdgeTouched(edgeFlags: Int, pointerId: Int) {}

        /**
         * Called when the given edge may become locked. This can happen if an edge drag
         * was preliminarily rejected before beginning, but after [.onEdgeTouched]
         * was called. This method should return true to lock this edge or false to leave it
         * unlocked. The default behavior is to leave edges unlocked.
         *
         * @param edgeFlags A combination of edge flags describing the edge(s) locked
         * @return true to lock the edge, false to leave it unlocked
         */
        open fun onEdgeLock(edgeFlags: Int): Boolean {
            return false
        }

        /**
         * Called when the user has started a deliberate drag away from one
         * of the subscribed edges in the parent view while no child view is currently captured.
         *
         * @param edgeFlags A combination of edge flags describing the edge(s) dragged
         * @param pointerId ID of the pointer touching the described edge(s)
         * @see .EDGE_LEFT
         *
         * @see .EDGE_TOP
         *
         * @see .EDGE_RIGHT
         *
         * @see .EDGE_BOTTOM
         */
        open fun onEdgeDragStarted(edgeFlags: Int, pointerId: Int) {}

        /**
         * Called to determine the Z-order of child views.
         *
         * @param index the ordered position to query for
         * @return index of the view that should be ordered at position `index`
         */
        open fun getOrderedChildIndex(index: Int): Int {
            return index
        }

        /**
         * Return the magnitude of a draggable child view's horizontal range of motion in pixels.
         * This method should return 0 for views that cannot move horizontally.
         *
         * @param child Child view to check
         * @return range of horizontal motion in pixels
         */
        open fun getViewHorizontalDragRange(child: View): Int {
            return 0
        }

        /**
         * Return the magnitude of a draggable child view's vertical range of motion in pixels.
         * This method should return 0 for views that cannot move vertically.
         *
         * @param child Child view to check
         * @return range of vertical motion in pixels
         */
        open fun getViewVerticalDragRange(child: View): Int {
            return 0
        }

        /**
         * Called when the user's input indicates that they want to capture the given child view
         * with the pointer indicated by pointerId. The callback should return true if the user
         * is permitted to drag the given view with the indicated pointer.
         *
         *
         * ViewDragHelper may call this method multiple times for the same view even if
         * the view is already captured; this indicates that a new pointer is trying to take
         * control of the view.
         *
         *
         * If this method returns true, a call to [.onViewCaptured]
         * will follow if the capture is successful.
         *
         * @param child Child the user is attempting to capture
         * @param pointerId ID of the pointer attempting the capture
         * @return true if capture should be allowed, false otherwise
         */
        abstract fun tryCaptureView(child: View, pointerId: Int): Boolean

        /**
         * Restrict the motion of the dragged child view along the horizontal axis.
         * The default implementation does not allow horizontal motion; the extending
         * class must override this method and provide the desired clamping.
         *
         *
         * @param child Child view being dragged
         * @param left Attempted motion along the X axis
         * @param dx Proposed change in position for left
         * @return The new clamped position for left
         */
        open fun clampViewPositionHorizontal(child: View, left: Int, dx: Int): Int {
            return 0
        }

        /**
         * Restrict the motion of the dragged child view along the vertical axis.
         * The default implementation does not allow vertical motion; the extending
         * class must override this method and provide the desired clamping.
         *
         *
         * @param child Child view being dragged
         * @param top Attempted motion along the Y axis
         * @param dy Proposed change in position for top
         * @return The new clamped position for top
         */
        open fun clampViewPositionVertical(child: View, top: Int, dy: Int): Int {
            return 0
        }
    }

    init {
        val vc = ViewConfiguration.get(context)
        val density = context.resources.displayMetrics.density
        edgeSize = (EDGE_SIZE * density + 0.5f).toInt()

        touchSlop = vc.scaledTouchSlop
        maxVelocity = vc.scaledMaximumFlingVelocity.toFloat()
        minVelocity = vc.scaledMinimumFlingVelocity.toFloat()
        scroller = ScrollerCompat.create(context, interpolator)
    }

    /**
     * Enable edge tracking for the selected edges of the parent view.
     * The callback's [Callback.onEdgeTouched] and
     * [Callback.onEdgeDragStarted] methods will only be invoked
     * for edges for which edge tracking has been enabled.
     *
     * @param edgeFlags Combination of edge flags describing the edges to watch
     * @see .EDGE_LEFT
     *
     * @see .EDGE_TOP
     *
     * @see .EDGE_RIGHT
     *
     * @see .EDGE_BOTTOM
     */
    fun setEdgeTrackingEnabled(edgeFlags: Int) {
        trackingEdges = edgeFlags
    }

    /**
     * Capture a specific child view for dragging within the parent. The callback will be notified
     * but [Callback.tryCaptureView] will not be asked permission to
     * capture this view.
     *
     * @param childView Child view to capture
     * @param activePointerId ID of the pointer that is dragging the captured child view
     */
    fun captureChildView(childView: View, activePointerId: Int) {
        require(!(childView.parent !== parentView)) {
            "captureChildView: parameter must be a descendant " +
                    "of the ViewDragHelper's tracked parent view (" + parentView + ")"
        }

        capturedView = childView
        this.activePointerId = activePointerId
        callback.onViewCaptured(childView, activePointerId)
        setDragState(STATE_DRAGGING)
    }

    /**
     * The result of a call to this method is equivalent to
     * [.processTouchEvent] receiving an ACTION_CANCEL event.
     */
    fun cancel() {
        activePointerId = INVALID_POINTER
        clearMotionHistory()

        if (velocityTracker != null) {
            velocityTracker!!.recycle()
            velocityTracker = null
        }
    }

    /**
     * [.cancel], but also abort all motion in progress and snap to the end of any
     * animation.
     */
    fun abort() {
        cancel()
        if (viewDragState == STATE_SETTLING) {
            val oldX = scroller.currX
            val oldY = scroller.currY
            scroller.abortAnimation()
            val newX = scroller.currX
            val newY = scroller.currY
            callback.onViewPositionChanged(capturedView, newX, newY, newX - oldX, newY - oldY)
        }
        setDragState(STATE_IDLE)
    }

    /**
     * Animate the view `child` to the given (left, top) position.
     * If this method returns true, the caller should invoke [.continueSettling]
     * on each subsequent frame to continue the motion until it returns false. If this method
     * returns false there is no further work to do to complete the movement.
     *
     *
     * This operation does not count as a capture event, though [.getCapturedView]
     * will still report the sliding view while the slide is in progress.
     *
     * @param child Child view to capture and animate
     * @param finalLeft Final left position of child
     * @param finalTop Final top position of child
     * @return true if animation should continue through [.continueSettling] calls
     */
    fun smoothSlideViewTo(child: View, finalLeft: Int, finalTop: Int): Boolean {
        capturedView = child
        activePointerId = INVALID_POINTER

        val continueSliding = forceSettleCapturedViewAt(finalLeft, finalTop, 0, 0)
        if (!continueSliding && viewDragState == STATE_IDLE && capturedView != null) {
            // If we're in an IDLE state to begin with and aren't moving anywhere, we
            // end up having a non-null capturedView with an IDLE dragState
            capturedView = null
        }

        return continueSliding
    }

    /**
     * Settle the captured view at the given (left, top) position.
     * The appropriate velocity from prior motion will be taken into account.
     * If this method returns true, the caller should invoke [.continueSettling]
     * on each subsequent frame to continue the motion until it returns false. If this method
     * returns false there is no further work to do to complete the movement.
     *
     * @param finalLeft Settled left edge position for the captured view
     * @param finalTop Settled top edge position for the captured view
     * @return true if animation should continue through [.continueSettling] calls
     */
    fun settleCapturedViewAt(finalLeft: Int, finalTop: Int): Boolean {
        check(releaseInProgress) { "Cannot settleCapturedViewAt outside of a call to " + "Callback#onViewReleased" }

        return forceSettleCapturedViewAt(
            finalLeft, finalTop,
            VelocityTrackerCompat.getXVelocity(velocityTracker!!, activePointerId).toInt(),
            VelocityTrackerCompat.getYVelocity(velocityTracker!!, activePointerId).toInt()
        )
    }

    /**
     * Settle the captured view at the given (left, top) position.
     *
     * @param finalLeft Target left position for the captured view
     * @param finalTop Target top position for the captured view
     * @param xvel Horizontal velocity
     * @param yvel Vertical velocity
     * @return true if animation should continue through [.continueSettling] calls
     */
    private fun forceSettleCapturedViewAt(
        finalLeft: Int,
        finalTop: Int,
        xvel: Int,
        yvel: Int
    ): Boolean {
        val startLeft = capturedView!!.x.toInt()
        val startTop = capturedView!!.y.toInt()
        val dx = finalLeft - startLeft
        val dy = finalTop - startTop

        if (dx == 0 && dy == 0) {
            // Nothing to do. Send callbacks, be done.
            scroller.abortAnimation()
            setDragState(STATE_IDLE)
            return false
        }

        val duration = computeSettleDuration(capturedView!!, dx, dy, xvel, yvel)
        scroller.startScroll(startLeft, startTop, dx, dy, duration)

        setDragState(STATE_SETTLING)
        return true
    }

    private fun computeSettleDuration(child: View, dx: Int, dy: Int, xvel: Int, yvel: Int): Int {
        var xvel = xvel
        var yvel = yvel
        xvel = clampMag(xvel, minVelocity.toInt(), maxVelocity.toInt())
        yvel = clampMag(yvel, minVelocity.toInt(), maxVelocity.toInt())
        val absDx = abs(dx)
        val absDy = abs(dy)
        val absXVel = abs(xvel)
        val absYVel = abs(yvel)
        val addedVel = absXVel + absYVel
        val addedDistance = absDx + absDy

        val xweight = if (xvel != 0)
            absXVel.toFloat() / addedVel
        else
            absDx.toFloat() / addedDistance
        val yweight = if (yvel != 0)
            absYVel.toFloat() / addedVel
        else
            absDy.toFloat() / addedDistance

        val xduration = computeAxisDuration(dx, xvel, callback.getViewHorizontalDragRange(child))
        val yduration = computeAxisDuration(dy, yvel, callback.getViewVerticalDragRange(child))

        return (xduration * xweight + yduration * yweight).toInt()
    }

    private fun computeAxisDuration(delta: Int, velocity: Int, motionRange: Int): Int {
        var velocity = velocity
        if (delta == 0) {
            return 0
        }

        val width = parentView.width
        val halfWidth = width / 2
        val distanceRatio = 1f.coerceAtMost(abs(delta).toFloat() / width)
        val distance = halfWidth + halfWidth * distanceInfluenceForSnapDuration(distanceRatio)

        val duration: Int
        velocity = abs(velocity)
        duration = if (velocity > 0) {
            4 * (1000 * abs(distance / velocity)).roundToInt()
        } else {
            val range = abs(delta).toFloat() / motionRange
            ((range + 1) * BASE_SETTLE_DURATION).toInt()
        }
        return duration.coerceAtMost(MAX_SETTLE_DURATION)
    }

    /**
     * Clamp the magnitude of value for absMin and absMax.
     * If the value is below the minimum, it will be clamped to zero.
     * If the value is above the maximum, it will be clamped to the maximum.
     *
     * @param value Value to clamp
     * @param absMin Absolute value of the minimum significant value to return
     * @param absMax Absolute value of the maximum value to return
     * @return The clamped value with the same sign as `value`
     */
    private fun clampMag(value: Int, absMin: Int, absMax: Int): Int {
        val absValue = abs(value)
        if (absValue < absMin) return 0
        return if (absValue > absMax) if (value > 0) absMax else -absMax else value
    }

    /**
     * Clamp the magnitude of value for absMin and absMax.
     * If the value is below the minimum, it will be clamped to zero.
     * If the value is above the maximum, it will be clamped to the maximum.
     *
     * @param value Value to clamp
     * @param absMin Absolute value of the minimum significant value to return
     * @param absMax Absolute value of the maximum value to return
     * @return The clamped value with the same sign as `value`
     */
    private fun clampMag(value: Float, absMin: Float, absMax: Float): Float {
        val absValue = abs(value)
        if (absValue < absMin) return 0f
        return if (absValue > absMax) if (value > 0) absMax else -absMax else value
    }

    private fun distanceInfluenceForSnapDuration(f: Float): Float {
        var f = f
        f -= 0.5f // center the values about 0.
        f *= (0.3f * Math.PI / 2.0f).toFloat()
        return sin(f.toDouble()).toFloat()
    }

    /**
     * Settle the captured view based on standard free-moving fling behavior.
     * The caller should invoke [.continueSettling] on each subsequent frame
     * to continue the motion until it returns false.
     *
     * @param minLeft Minimum X position for the view's left edge
     * @param minTop Minimum Y position for the view's top edge
     * @param maxLeft Maximum X position for the view's left edge
     * @param maxTop Maximum Y position for the view's top edge
     */
    fun flingCapturedView(minLeft: Int, minTop: Int, maxLeft: Int, maxTop: Int) {
        check(releaseInProgress) { "Cannot flingCapturedView outside of a call to " + "Callback#onViewReleased" }

        scroller.fling(
            capturedView!!.x.toInt(), capturedView!!.y.toInt(),
            VelocityTrackerCompat.getXVelocity(velocityTracker!!, activePointerId).toInt(),
            VelocityTrackerCompat.getYVelocity(velocityTracker!!, activePointerId).toInt(),
            minLeft, maxLeft, minTop, maxTop
        )

        setDragState(STATE_SETTLING)
    }

    /**
     * Move the captured settling view by the appropriate amount for the current time.
     * If `continueSettling` returns true, the caller should call it again
     * on the next frame to continue.
     *
     * @param deferCallbacks true if state callbacks should be deferred via posted message.
     * Set this to true if you are calling this method from
     * [View.computeScroll] or similar methods
     * invoked as part of layout or drawing.
     * @return true if settle is still in progress
     */
    fun continueSettling(deferCallbacks: Boolean): Boolean {
        if (viewDragState == STATE_SETTLING) {
            var keepGoing = scroller.computeScrollOffset()
            val x = scroller.currX
            val y = scroller.currY
            val dx = (x - capturedView!!.x).toInt()
            val dy = (y - capturedView!!.y).toInt()

            if (dx != 0) {
                ViewCompat.offsetLeftAndRight(capturedView!!, dx)
            }
            if (dy != 0) {
                ViewCompat.offsetTopAndBottom(capturedView!!, dy)
            }

            if (dx != 0 || dy != 0) {
                callback.onViewPositionChanged(capturedView, x, y, dx, dy)
            }

            if (keepGoing && x == scroller.finalX && y == scroller.finalY) {
                // Close enough. The interpolator/scroller might think we're still moving
                // but the user sure doesn't.
                scroller.abortAnimation()
                keepGoing = false
            }

            if (!keepGoing) {
                if (deferCallbacks) {
                    parentView.post(setIdleRunnable)
                } else {
                    setDragState(STATE_IDLE)
                }
            }
        }

        return viewDragState == STATE_SETTLING
    }

    /**
     * Like all callback events this must happen on the UI thread, but release
     * involves some extra semantics. During a release (releaseInProgress)
     * is the only time it is valid to call [.settleCapturedViewAt]
     * or [.flingCapturedView].
     */
    private fun dispatchViewReleased(xvel: Float, yvel: Float) {
        releaseInProgress = true
        callback.onViewReleased(capturedView, xvel, yvel)
        releaseInProgress = false

        if (viewDragState == STATE_DRAGGING) {
            // onViewReleased didn't call a method that would have changed this. Go idle.
            setDragState(STATE_IDLE)
        }
    }

    private fun clearMotionHistory() {
        if (initialMotionX == null) {
            return
        }
        Arrays.fill(initialMotionX!!, 0f)
        Arrays.fill(initialMotionY!!, 0f)
        Arrays.fill(lastMotionX!!, 0f)
        Arrays.fill(lastMotionY!!, 0f)
        Arrays.fill(initialEdgesTouched!!, 0)
        Arrays.fill(edgeDragsInProgress!!, 0)
        Arrays.fill(edgeDragsLocked!!, 0)
        pointersDown = 0
    }

    private fun clearMotionHistory(pointerId: Int) {
        if (initialMotionX == null || !isPointerDown(pointerId)) {
            return
        }
        initialMotionX!![pointerId] = 0f
        initialMotionY!![pointerId] = 0f
        lastMotionX!![pointerId] = 0f
        lastMotionY!![pointerId] = 0f
        initialEdgesTouched!![pointerId] = 0
        edgeDragsInProgress!![pointerId] = 0
        edgeDragsLocked!![pointerId] = 0
        pointersDown = pointersDown and (1 shl pointerId).inv()
    }

    private fun ensureMotionHistorySizeForId(pointerId: Int) {
        if (initialMotionX == null || initialMotionX!!.size <= pointerId) {
            val imx = FloatArray(pointerId + 1)
            val imy = FloatArray(pointerId + 1)
            val lmx = FloatArray(pointerId + 1)
            val lmy = FloatArray(pointerId + 1)
            val iit = IntArray(pointerId + 1)
            val edip = IntArray(pointerId + 1)
            val edl = IntArray(pointerId + 1)

            if (initialMotionX != null) {
                System.arraycopy(initialMotionX!!, 0, imx, 0, initialMotionX!!.size)
                System.arraycopy(initialMotionY!!, 0, imy, 0, initialMotionY!!.size)
                System.arraycopy(lastMotionX!!, 0, lmx, 0, lastMotionX!!.size)
                System.arraycopy(lastMotionY!!, 0, lmy, 0, lastMotionY!!.size)
                System.arraycopy(initialEdgesTouched!!, 0, iit, 0, initialEdgesTouched!!.size)
                System.arraycopy(edgeDragsInProgress!!, 0, edip, 0, edgeDragsInProgress!!.size)
                System.arraycopy(edgeDragsLocked!!, 0, edl, 0, edgeDragsLocked!!.size)
            }

            initialMotionX = imx
            initialMotionY = imy
            lastMotionX = lmx
            lastMotionY = lmy
            initialEdgesTouched = iit
            edgeDragsInProgress = edip
            edgeDragsLocked = edl
        }
    }

    private fun saveInitialMotion(x: Float, y: Float, pointerId: Int) {
        ensureMotionHistorySizeForId(pointerId)
        lastMotionX!![pointerId] = x
        initialMotionX!![pointerId] = lastMotionX!![pointerId]
        lastMotionY!![pointerId] = y
        initialMotionY!![pointerId] = lastMotionY!![pointerId]
        initialEdgesTouched!![pointerId] = getEdgesTouched(x.toInt(), y.toInt())
        pointersDown = pointersDown or (1 shl pointerId)
    }

    private fun saveLastMotion(ev: MotionEvent) {
        val pointerCount = ev.pointerCount
        for (i in 0 until pointerCount) {
            val pointerId = ev.getPointerId(i)
            // If pointer is invalid then skip saving on ACTION_MOVE.
            if (!isValidPointerForActionMove(pointerId)) {
                continue
            }
            val x = ev.getX(i)
            val y = ev.getY(i)
            lastMotionX!![pointerId] = x
            lastMotionY!![pointerId] = y
        }
    }

    /**
     * Check if the given pointer ID represents a pointer that is currently down (to the best
     * of the ViewDragHelper's knowledge).
     *
     *
     * The state used to report this information is populated by the methods
     * [.shouldInterceptTouchEvent] or
     * [.processTouchEvent]. If one of these methods has not
     * been called for all relevant MotionEvents to track, the information reported
     * by this method may be stale or incorrect.
     *
     * @param pointerId pointer ID to check; corresponds to IDs provided by MotionEvent
     * @return true if the pointer with the given ID is still down
     */
    fun isPointerDown(pointerId: Int): Boolean {
        return pointersDown and (1 shl pointerId) != 0
    }

    private fun setDragState(state: Int) {
        parentView.removeCallbacks(setIdleRunnable)
        if (viewDragState != state) {
            viewDragState = state
            callback.onViewDragStateChanged(state)
            if (viewDragState == STATE_IDLE) {
                capturedView = null
            }
        }
    }

    /**
     * Attempt to capture the view with the given pointer ID. The callback will be involved.
     * This will put us into the "dragging" state. If we've already captured this view with
     * this pointer this method will immediately return true without consulting the callback.
     *
     * @param toCapture View to capture
     * @param pointerId Pointer to capture with
     * @return true if capture was successful
     */
    private fun tryCaptureViewForDrag(toCapture: View?, pointerId: Int): Boolean {
        if (toCapture === capturedView && activePointerId == pointerId) {
            // Already done!
            return true
        }
        if (toCapture != null && callback.tryCaptureView(toCapture, pointerId)) {
            activePointerId = pointerId
            captureChildView(toCapture, pointerId)
            return true
        }
        return false
    }

    /**
     * Tests scrollability within child views of v given a delta of dx.
     *
     * @param v View to test for horizontal scrollability
     * @param checkV Whether the view v passed should itself be checked for scrollability (true),
     * or just its children (false).
     * @param dx Delta scrolled in pixels along the X axis
     * @param dy Delta scrolled in pixels along the Y axis
     * @param x X coordinate of the active touch point
     * @param y Y coordinate of the active touch point
     * @return true if child views of v can be scrolled by delta of dx.
     */
    protected fun canScroll(v: View, checkV: Boolean, dx: Int, dy: Int, x: Int, y: Int): Boolean {
        if (v is ViewGroup) {
            val scrollX = v.getScrollX()
            val scrollY = v.getScrollY()
            val count = v.childCount
            // Count backwards - let topmost views consume scroll distance first.
            for (i in count - 1 downTo 0) {
                // TODO: Add versioned support here for transformed views.
                // This will not work for transformed views in Honeycomb+
                val child = v.getChildAt(i)
                if (x + scrollX >= child.x && x + scrollX < child.x + child.width &&
                    y + scrollY >= child.y && y + scrollY < child.y + child.height &&
                    canScroll(
                        child, true, dx, dy, (x + scrollX - child.x).toInt(),
                        (y + scrollY - child.y).toInt()
                    )
                ) {
                    return true
                }
            }
        }

        return checkV && (ViewCompat.canScrollHorizontally(
            v,
            -dx
        ) || ViewCompat.canScrollVertically(v, -dy))
    }

    /**
     * Check if this event as provided to the parent view's onInterceptTouchEvent should
     * cause the parent to intercept the touch event stream.
     *
     * @param ev MotionEvent provided to onInterceptTouchEvent
     * @return true if the parent view should return true from onInterceptTouchEvent
     */
    fun shouldInterceptTouchEvent(ev: MotionEvent): Boolean {
        val action = MotionEventCompat.getActionMasked(ev)
        val actionIndex = MotionEventCompat.getActionIndex(ev)

        if (action == MotionEvent.ACTION_DOWN) {
            // Reset things for a new event stream, just in case we didn't get
            // the whole previous stream.
            cancel()
        }

        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker!!.addMovement(ev)

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                val x = ev.x
                val y = ev.y
                val pointerId = ev.getPointerId(0)
                saveInitialMotion(x, y, pointerId)

                val toCapture = findTopChildUnder(x.toInt(), y.toInt())

                // Catch a settling view if possible.
                if (toCapture === capturedView && viewDragState == STATE_SETTLING) {
                    tryCaptureViewForDrag(toCapture, pointerId)
                }

                val edgesTouched = initialEdgesTouched!![pointerId]
                if (edgesTouched and trackingEdges != 0) {
                    callback.onEdgeTouched(edgesTouched and trackingEdges, pointerId)
                }
            }

            MotionEventCompat.ACTION_POINTER_DOWN -> {
                val pointerId = ev.getPointerId(actionIndex)
                val x = ev.getX(actionIndex)
                val y = ev.getY(actionIndex)

                saveInitialMotion(x, y, pointerId)

                // A ViewDragHelper can only manipulate one view at a time.
                if (viewDragState == STATE_IDLE) {
                    val edgesTouched = initialEdgesTouched!![pointerId]
                    if (edgesTouched and trackingEdges != 0) {
                        callback.onEdgeTouched(edgesTouched and trackingEdges, pointerId)
                    }
                } else if (viewDragState == STATE_SETTLING) {
                    // Catch a settling view if possible.
                    val toCapture = findTopChildUnder(x.toInt(), y.toInt())
                    if (toCapture === capturedView) {
                        tryCaptureViewForDrag(toCapture, pointerId)
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (initialMotionX == null || initialMotionY == null) return viewDragState == STATE_DRAGGING

                // First to cross a touch slop over a draggable view wins. Also report edge drags.
                val pointerCount = ev.pointerCount
                for (i in 0 until pointerCount) {
                    val pointerId = ev.getPointerId(i)

                    // If pointer is invalid then skip the ACTION_MOVE.
                    if (!isValidPointerForActionMove(pointerId)) continue

                    val x = ev.getX(i)
                    val y = ev.getY(i)
                    val dx = x - initialMotionX!![pointerId]
                    val dy = y - initialMotionY!![pointerId]

                    val toCapture = findTopChildUnder(x.toInt(), y.toInt())
                    val pastSlop = toCapture != null && checkTouchSlop(toCapture, dx, dy)
                    if (pastSlop) {
                        // check the callback's
                        // getView[Horizontal|Vertical]DragRange methods to know
                        // if you can move at all along an axis, then see if it
                        // would clamp to the same value. If you can't move at
                        // all in every dimension with a nonzero range, bail.
                        val oldLeft = toCapture!!.left
                        val targetLeft = oldLeft + dx.toInt()
                        val newLeft = callback.clampViewPositionHorizontal(
                            toCapture,
                            targetLeft, dx.toInt()
                        )
                        val oldTop = toCapture.top
                        val targetTop = oldTop + dy.toInt()
                        val newTop = callback.clampViewPositionVertical(
                            toCapture, targetTop,
                            dy.toInt()
                        )
                        val horizontalDragRange = callback.getViewHorizontalDragRange(
                            toCapture
                        )
                        val verticalDragRange = callback.getViewVerticalDragRange(toCapture)
                        if ((horizontalDragRange == 0 || horizontalDragRange > 0 && newLeft == oldLeft) && (verticalDragRange == 0 || verticalDragRange > 0 && newTop == oldTop)) {
                            break
                        }
                    }
                    reportNewEdgeDrags(dx, dy, pointerId)
                    if (viewDragState == STATE_DRAGGING) {
                        // Callback might have started an edge drag
                        break
                    }

                    if (pastSlop && tryCaptureViewForDrag(toCapture, pointerId)) {
                        break
                    }
                }
                saveLastMotion(ev)
            }

            MotionEventCompat.ACTION_POINTER_UP -> {
                val pointerId = ev.getPointerId(actionIndex)
                clearMotionHistory(pointerId)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancel()
            }
        }

        return viewDragState == STATE_DRAGGING
    }

    /**
     * Process a touch event received by the parent view. This method will dispatch callback events
     * as needed before returning. The parent view's onTouchEvent implementation should call this.
     *
     * @param ev The touch event received by the parent view
     */
    fun processTouchEvent(ev: MotionEvent) {
        val action = MotionEventCompat.getActionMasked(ev)
        val actionIndex = MotionEventCompat.getActionIndex(ev)

        if (action == MotionEvent.ACTION_DOWN) {
            // Reset things for a new event stream, just in case we didn't get
            // the whole previous stream.
            cancel()
        }

        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker!!.addMovement(ev)

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                val x = ev.x
                val y = ev.y
                val pointerId = ev.getPointerId(0)
                val toCapture = findTopChildUnder(x.toInt(), y.toInt())

                saveInitialMotion(x, y, pointerId)

                // Since the parent is already directly processing this touch event,
                // there is no reason to delay for a slop before dragging.
                // Start immediately if possible.
                tryCaptureViewForDrag(toCapture, pointerId)

                val edgesTouched = initialEdgesTouched!![pointerId]
                if (edgesTouched and trackingEdges != 0) {
                    callback.onEdgeTouched(edgesTouched and trackingEdges, pointerId)
                }
            }

            MotionEventCompat.ACTION_POINTER_DOWN -> {
                val pointerId = ev.getPointerId(actionIndex)
                val x = ev.getX(actionIndex)
                val y = ev.getY(actionIndex)

                saveInitialMotion(x, y, pointerId)

                // A ViewDragHelper can only manipulate one view at a time.
                if (viewDragState == STATE_IDLE) {
                    // If we're idle we can do anything! Treat it like a normal down event.

                    val toCapture = findTopChildUnder(x.toInt(), y.toInt())
                    tryCaptureViewForDrag(toCapture, pointerId)

                    val edgesTouched = initialEdgesTouched!![pointerId]
                    if (edgesTouched and trackingEdges != 0) {
                        callback.onEdgeTouched(edgesTouched and trackingEdges, pointerId)
                    }
                } else if (isCapturedViewUnder(x.toInt(), y.toInt())) {
                    // We're still tracking a captured view. If the same view is under this
                    // point, we'll swap to controlling it with this pointer instead.
                    // (This will still work if we're "catching" a settling view.)

                    tryCaptureViewForDrag(capturedView, pointerId)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (viewDragState == STATE_DRAGGING) {
                    // If pointer is invalid then skip the ACTION_MOVE.
                    if (!isValidPointerForActionMove(activePointerId)) return

                    val index = ev.findPointerIndex(activePointerId)
                    val x = ev.getX(index)
                    val y = ev.getY(index)
                    val idx = (x - lastMotionX!![activePointerId]).toInt()
                    val idy = (y - lastMotionY!![activePointerId]).toInt()

                    dragTo(
                        (capturedView!!.x + idx).toInt(),
                        (capturedView!!.y + idy).toInt(),
                        idx,
                        idy
                    )

                    saveLastMotion(ev)
                } else {
                    // Check to see if any pointer is now over a draggable view.
                    val pointerCount = ev.pointerCount
                    for (i in 0 until pointerCount) {
                        val pointerId = ev.getPointerId(i)

                        // If pointer is invalid then skip the ACTION_MOVE.
                        if (!isValidPointerForActionMove(pointerId)) continue

                        val x = ev.getX(i)
                        val y = ev.getY(i)
                        val dx = x - initialMotionX!![pointerId]
                        val dy = y - initialMotionY!![pointerId]

                        reportNewEdgeDrags(dx, dy, pointerId)
                        if (viewDragState == STATE_DRAGGING) {
                            // Callback might have started an edge drag.
                            break
                        }

                        val toCapture = findTopChildUnder(x.toInt(), y.toInt())
                        if (checkTouchSlop(toCapture, dx, dy) && tryCaptureViewForDrag(
                                toCapture,
                                pointerId
                            )
                        ) {
                            break
                        }
                    }
                    saveLastMotion(ev)
                }
            }

            MotionEventCompat.ACTION_POINTER_UP -> {
                val pointerId = ev.getPointerId(actionIndex)
                if (viewDragState == STATE_DRAGGING && pointerId == activePointerId) {
                    // Try to find another pointer that's still holding on to the captured view.
                    var newActivePointer = INVALID_POINTER
                    val pointerCount = ev.pointerCount
                    for (i in 0 until pointerCount) {
                        val id = ev.getPointerId(i)
                        if (id == activePointerId) {
                            // This one's going away, skip.
                            continue
                        }

                        val x = ev.getX(i)
                        val y = ev.getY(i)
                        if (findTopChildUnder(
                                x.toInt(),
                                y.toInt()
                            ) === capturedView && tryCaptureViewForDrag(capturedView, id)
                        ) {
                            newActivePointer = activePointerId
                            break
                        }
                    }

                    if (newActivePointer == INVALID_POINTER) {
                        // We didn't find another pointer still touching the view, release it.
                        releaseViewForPointerUp()
                    }
                }
                clearMotionHistory(pointerId)
            }

            MotionEvent.ACTION_UP -> {
                if (viewDragState == STATE_DRAGGING) {
                    releaseViewForPointerUp()
                }
                cancel()
            }

            MotionEvent.ACTION_CANCEL -> {
                if (viewDragState == STATE_DRAGGING) {
                    dispatchViewReleased(0f, 0f)
                }
                cancel()
            }
        }
    }

    private fun reportNewEdgeDrags(dx: Float, dy: Float, pointerId: Int) {
        var dragsStarted = 0
        if (checkNewEdgeDrag(dx, dy, pointerId, EDGE_LEFT)) {
            dragsStarted = dragsStarted or EDGE_LEFT
        }
        if (checkNewEdgeDrag(dy, dx, pointerId, EDGE_TOP)) {
            dragsStarted = dragsStarted or EDGE_TOP
        }
        if (checkNewEdgeDrag(dx, dy, pointerId, EDGE_RIGHT)) {
            dragsStarted = dragsStarted or EDGE_RIGHT
        }
        if (checkNewEdgeDrag(dy, dx, pointerId, EDGE_BOTTOM)) {
            dragsStarted = dragsStarted or EDGE_BOTTOM
        }

        if (dragsStarted != 0) {
            edgeDragsInProgress!![pointerId] = edgeDragsInProgress!![pointerId] or dragsStarted
            callback.onEdgeDragStarted(dragsStarted, pointerId)
        }
    }

    private fun checkNewEdgeDrag(delta: Float, odelta: Float, pointerId: Int, edge: Int): Boolean {
        val absDelta = abs(delta)
        val absODelta = abs(odelta)

        if (initialEdgesTouched!![pointerId] and edge != edge || trackingEdges and edge == 0 ||
            edgeDragsLocked!![pointerId] and edge == edge ||
            edgeDragsInProgress!![pointerId] and edge == edge ||
            absDelta <= touchSlop && absODelta <= touchSlop
        ) {
            return false
        }
        if (absDelta < absODelta * 0.5f && callback.onEdgeLock(edge)) {
            edgeDragsLocked!![pointerId] = edgeDragsLocked!![pointerId] or edge
            return false
        }
        return edgeDragsInProgress!![pointerId] and edge == 0 && absDelta > touchSlop
    }

    /**
     * Check if we've crossed a reasonable touch slop for the given child view.
     * If the child cannot be dragged along the horizontal or vertical axis, motion
     * along that axis will not count toward the slop check.
     *
     * @param child Child to check
     * @param dx Motion since initial position along X axis
     * @param dy Motion since initial position along Y axis
     * @return true if the touch slop has been crossed
     */
    private fun checkTouchSlop(child: View?, dx: Float, dy: Float): Boolean {
        if (child == null) {
            return false
        }
        val checkHorizontal = callback.getViewHorizontalDragRange(child) > 0
        val checkVertical = callback.getViewVerticalDragRange(child) > 0

        if (checkHorizontal && checkVertical) {
            return dx * dx + dy * dy > touchSlop * touchSlop
        } else if (checkHorizontal) {
            return abs(dx) > touchSlop
        } else if (checkVertical) {
            return abs(dy) > touchSlop
        }
        return false
    }

    /**
     * Check if any pointer tracked in the current gesture has crossed
     * the required slop threshold.
     *
     *
     * This depends on internal state populated by
     * [.shouldInterceptTouchEvent] or
     * [.processTouchEvent]. You should only rely on
     * the results of this method after all currently available touch data
     * has been provided to one of these two methods.
     *
     * @param directions Combination of direction flags, see [.DIRECTION_HORIZONTAL],
     * [.DIRECTION_VERTICAL], [.DIRECTION_ALL]
     * @return true if the slop threshold has been crossed, false otherwise
     */
    fun checkTouchSlop(directions: Int): Boolean {
        val count = initialMotionX!!.size
        for (i in 0 until count) {
            if (checkTouchSlop(directions, i)) {
                return true
            }
        }
        return false
    }

    /**
     * Check if the specified pointer tracked in the current gesture has crossed
     * the required slop threshold.
     *
     *
     * This depends on internal state populated by
     * [.shouldInterceptTouchEvent] or
     * [.processTouchEvent]. You should only rely on
     * the results of this method after all currently available touch data
     * has been provided to one of these two methods.
     *
     * @param directions Combination of direction flags, see [.DIRECTION_HORIZONTAL],
     * [.DIRECTION_VERTICAL], [.DIRECTION_ALL]
     * @param pointerId ID of the pointer to slop check as specified by MotionEvent
     * @return true if the slop threshold has been crossed, false otherwise
     */
    fun checkTouchSlop(directions: Int, pointerId: Int): Boolean {
        if (!isPointerDown(pointerId)) {
            return false
        }

        val checkHorizontal = directions and DIRECTION_HORIZONTAL == DIRECTION_HORIZONTAL
        val checkVertical = directions and DIRECTION_VERTICAL == DIRECTION_VERTICAL

        val dx = lastMotionX!![pointerId] - initialMotionX!![pointerId]
        val dy = lastMotionY!![pointerId] - initialMotionY!![pointerId]

        if (checkHorizontal && checkVertical) {
            return dx * dx + dy * dy > touchSlop * touchSlop
        } else if (checkHorizontal) {
            return abs(dx) > touchSlop
        } else if (checkVertical) {
            return abs(dy) > touchSlop
        }
        return false
    }

    /**
     * Check if any of the edges specified were initially touched in the currently active gesture.
     * If there is no currently active gesture this method will return false.
     *
     * @param edges Edges to check for an initial edge touch. See [.EDGE_LEFT],
     * [.EDGE_TOP], [.EDGE_RIGHT], [.EDGE_BOTTOM] and
     * [.EDGE_ALL]
     * @return true if any of the edges specified were initially touched in the current gesture
     */
    fun isEdgeTouched(edges: Int): Boolean {
        val count = initialEdgesTouched!!.size
        for (i in 0 until count) {
            if (isEdgeTouched(edges, i)) {
                return true
            }
        }
        return false
    }

    /**
     * Check if any of the edges specified were initially touched by the pointer with
     * the specified ID. If there is no currently active gesture or if there is no pointer with
     * the given ID currently down this method will return false.
     *
     * @param edges Edges to check for an initial edge touch. See [.EDGE_LEFT],
     * [.EDGE_TOP], [.EDGE_RIGHT], [.EDGE_BOTTOM] and
     * [.EDGE_ALL]
     * @return true if any of the edges specified were initially touched in the current gesture
     */
    fun isEdgeTouched(edges: Int, pointerId: Int): Boolean {
        return isPointerDown(pointerId) && initialEdgesTouched!![pointerId] and edges != 0
    }

    private fun releaseViewForPointerUp() {
        velocityTracker!!.computeCurrentVelocity(1000, maxVelocity)
        val xvel = clampMag(
            VelocityTrackerCompat.getXVelocity(velocityTracker!!, activePointerId),
            minVelocity, maxVelocity
        )
        val yvel = clampMag(
            VelocityTrackerCompat.getYVelocity(velocityTracker!!, activePointerId),
            minVelocity, maxVelocity
        )
        dispatchViewReleased(xvel, yvel)
    }

    private fun dragTo(left: Int, top: Int, dx: Int, dy: Int) {
        var clampedX = left
        var clampedY = top
        val oldLeft = capturedView!!.x.toInt()
        val oldTop = capturedView!!.y.toInt()
        if (dx != 0) {
            clampedX = callback.clampViewPositionHorizontal(capturedView!!, left, dx)
            ViewCompat.offsetLeftAndRight(capturedView!!, clampedX - oldLeft)
        }
        if (dy != 0) {
            clampedY = callback.clampViewPositionVertical(capturedView!!, top, dy)
            ViewCompat.offsetTopAndBottom(capturedView!!, clampedY - oldTop)
        }

        if (dx != 0 || dy != 0) {
            val clampedDx = clampedX - oldLeft
            val clampedDy = clampedY - oldTop
            callback.onViewPositionChanged(
                capturedView, clampedX, clampedY,
                clampedDx, clampedDy
            )
        }
    }

    /**
     * Determine if the currently captured view is under the given point in the
     * parent view's coordinate system. If there is no captured view this method
     * will return false.
     *
     * @param x X position to test in the parent's coordinate system
     * @param y Y position to test in the parent's coordinate system
     * @return true if the captured view is under the given point, false otherwise
     */
    fun isCapturedViewUnder(x: Int, y: Int): Boolean {
        return isViewUnder(capturedView, x, y)
    }

    /**
     * Determine if the supplied view is under the given point in the
     * parent view's coordinate system.
     *
     * @param view Child view of the parent to hit test
     * @param x X position to test in the parent's coordinate system
     * @param y Y position to test in the parent's coordinate system
     * @return true if the supplied view is under the given point, false otherwise
     */
    fun isViewUnder(view: View?, x: Int, y: Int): Boolean {
        return if (view == null) {
            false
        } else x >= view.x &&
                x < view.x + view.width &&
                y >= view.y &&
                y < view.y + view.height
    }

    /**
     * Find the topmost child under the given point within the parent view's coordinate system.
     * The child order is determined using [Callback.getOrderedChildIndex].
     *
     * @param x X position to test in the parent's coordinate system
     * @param y Y position to test in the parent's coordinate system
     * @return The topmost child view under (x, y) or null if none found.
     */
    fun findTopChildUnder(x: Int, y: Int): View? {
        val childCount = parentView.childCount
        for (i in childCount - 1 downTo 0) {
            val child = parentView.getChildAt(callback.getOrderedChildIndex(i))
            if (x >= child.x && x < child.x + child.width &&
                y >= child.y && y < child.y + child.height
            ) {
                return child
            }
        }
        return null
    }

    private fun getEdgesTouched(x: Int, y: Int): Int {
        var result = 0

        if (x < parentView.x + edgeSize) result = result or EDGE_LEFT
        if (y < parentView.y + edgeSize) result = result or EDGE_TOP
        if (x > parentView.x + parentView.width - edgeSize) result = result or EDGE_RIGHT
        if (y > parentView.y + parentView.height - edgeSize) result = result or EDGE_BOTTOM

        return result
    }

    private fun isValidPointerForActionMove(pointerId: Int): Boolean {
        if (!isPointerDown(pointerId)) {
            Log.e(
                TAG, "Ignoring pointerId=" + pointerId + " because ACTION_DOWN was not received "
                        + "for this pointer before ACTION_MOVE. It likely happened because "
                        + " ViewDragHelper did not receive all the events in the event stream."
            )
            return false
        }
        return true
    }
}

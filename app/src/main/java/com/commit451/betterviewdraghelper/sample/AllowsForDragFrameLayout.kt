package com.commit451.betterviewdraghelper.sample

import android.annotation.TargetApi
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

import com.commit451.translationviewdraghelper.TranslationViewDragHelper

/**
 * FrameLayout that allows dragging its inner views around
 */
class AllowsForDragFrameLayout : FrameLayout {

    private var viewDragHelper: TranslationViewDragHelper

    private var callback: TranslationViewDragHelper.Callback = object : TranslationViewDragHelper.Callback() {
        override fun tryCaptureView(child: View, pointerId: Int): Boolean {
            //Any children can be captured
            return true
        }

        override fun clampViewPositionHorizontal(child: View, left: Int, dx: Int): Int {
            //allow full movement along horizontal axis
            return left
        }

        override fun clampViewPositionVertical(child: View, top: Int, dy: Int): Int {
            //allow full movement along vertical axis
            return top
        }
    }

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    @TargetApi(21)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)

    init {
        viewDragHelper = TranslationViewDragHelper.create(this, 1.0f, callback)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return viewDragHelper.shouldInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        viewDragHelper.processTouchEvent(event)
        return true
    }
}

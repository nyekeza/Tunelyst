package com.tunelyst.player

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.view.View

/** Gentle press feedback; Android's animator-duration setting controls the motion. */
internal object PressFeedback {
    fun apply(view: View) {
        fun scale(value: Float, durationMs: Long) = AnimatorSet().apply {
            playTogether(ObjectAnimator.ofFloat(view, View.SCALE_X, value), ObjectAnimator.ofFloat(view, View.SCALE_Y, value))
            duration = durationMs
        }
        view.stateListAnimator = StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed), scale(0.97f, 90))
            addState(intArrayOf(), scale(1f, 150))
        }
    }
}

package com.example.kuwago

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class SplashActivity : AppCompatActivity() {

    private companion object {
        private const val SEGMENT_DURATION = 400L
        private const val SEGMENT_DELAY = 400L
        private const val FINAL_HOLD_DURATION = 400L
    }

    private var animatorSet: AnimatorSet? = null
    private var hasNavigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContentView(R.layout.activity_splash)

        val splashRoot = findViewById<FrameLayout>(R.id.splash_root)
        val logoBlack = findViewById<ImageView>(R.id.logo_black)
        val logoTextHat = findViewById<ImageView>(R.id.logo_text_hat)

        ViewCompat.setOnApplyWindowInsetsListener(splashRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        runLaunchAnimation(splashRoot, logoBlack, logoTextHat)
    }

    private fun runLaunchAnimation(
        splashRoot: FrameLayout,
        logoBlack: ImageView,
        logoTextHat: ImageView
    ) {
        val easeInOutCurve = PathInterpolator(0.42f, 0f, 0.58f, 1f)

        fun createPause(): ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SEGMENT_DELAY
        }

        // 1. Black to White background (800ms) + Black Logo Fade In
        val fadeToWhiteAnimator = ValueAnimator.ofObject(ArgbEvaluator(), Color.BLACK, Color.WHITE).apply {
            duration = SEGMENT_DURATION
            interpolator = easeInOutCurve
            addUpdateListener { animator ->
                splashRoot.setBackgroundColor(animator.animatedValue as Int)
                logoBlack.alpha = animator.animatedFraction
            }
        }

        // 2. Rotation: 0° -> +30° (800ms)
        val rotPositiveAnimator = ObjectAnimator.ofFloat(logoBlack, View.ROTATION, 0f, 30f).apply {
            duration = SEGMENT_DURATION
            interpolator = easeInOutCurve
        }

        // 3. Rotation: +30° -> -30° (800ms)
        val rotNegativeAnimator = ObjectAnimator.ofFloat(logoBlack, View.ROTATION, 30f, -30f).apply {
            duration = SEGMENT_DURATION
            interpolator = easeInOutCurve
        }

        // 4. Rotation: -30° -> 0° (800ms)
        val rotCenterAnimator = ObjectAnimator.ofFloat(logoBlack, View.ROTATION, -30f, 0f).apply {
            duration = SEGMENT_DURATION
            interpolator = easeInOutCurve
        }

        // 5. White to Black background (800ms) + Black Logo Fade Out
        val fadeToBlackAnimator = ValueAnimator.ofObject(ArgbEvaluator(), Color.WHITE, Color.BLACK).apply {
            duration = SEGMENT_DURATION
            interpolator = easeInOutCurve
            addUpdateListener { animator ->
                splashRoot.setBackgroundColor(animator.animatedValue as Int)
                logoBlack.alpha = 1f - animator.animatedFraction
            }
        }

        // 6. Final logo reveal (800ms)
        val finalLogoAnimator = ObjectAnimator.ofFloat(logoTextHat, View.ALPHA, 0f, 1f).apply {
            duration = SEGMENT_DURATION
            interpolator = easeInOutCurve
        }

        // 7. Final hold (400ms)
        val holdAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FINAL_HOLD_DURATION
        }

        animatorSet = AnimatorSet().apply {
            playSequentially(
                fadeToWhiteAnimator,
                createPause(),
                rotPositiveAnimator,
                createPause(),
                rotNegativeAnimator,
                createPause(),
                rotCenterAnimator,
                createPause(),
                fadeToBlackAnimator,
                createPause(),
                finalLogoAnimator,
                holdAnimator
            )
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isFinishing && !isDestroyed) {
                        navigateToMain()
                    }
                }
            })
            start()
        }
    }

    private fun navigateToMain() {
        if (hasNavigated) return
        hasNavigated = true

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            intent?.extras?.let { putExtras(it) }
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(mainIntent)
        finish()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        animatorSet?.cancel()
        animatorSet = null
    }
}

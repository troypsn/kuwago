package com.example.kuwago

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OnboardingAppSelectionFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_onboarding_app_selection, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val list = view.findViewById<RecyclerView>(R.id.rv_onboarding_apps)
        val loadingLayout = view.findViewById<View>(R.id.layout_apps_loading)
        val emptyView = view.findViewById<View>(R.id.tv_onboarding_no_apps)
        val label = view.findViewById<View>(R.id.tv_loading_apps_label)

        list.layoutManager = LinearLayoutManager(requireContext())

        val pulseAnimator = ObjectAnimator.ofFloat(label, "alpha", 0.4f, 1.0f).apply {
            duration = 750
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val context = requireContext()
            val startTime = System.currentTimeMillis()
            val enabled = SettingsAppSelectionFragment.getSavedEnabledAppPackages(context)
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(intent, 0)
                .filter {
                    it.activityInfo.packageName != context.packageName &&
                    SettingsAppSelectionFragment.DEFAULT_MESSAGING_PACKAGES.contains(it.activityInfo.packageName)
                }
                .map {
                    InstalledAppInfo(
                        it.loadLabel(pm).toString(),
                        it.activityInfo.packageName,
                        it.loadIcon(pm),
                        enabled.contains(it.activityInfo.packageName)
                    )
                }
                .sortedBy { it.appName.lowercase() }

            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed < 450) {
                delay(450 - elapsed)
            }

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                pulseAnimator.cancel()

                if (apps.isEmpty()) {
                    loadingLayout.animate().alpha(0f).setDuration(200).withEndAction {
                        loadingLayout.visibility = View.GONE
                        emptyView.alpha = 0f
                        emptyView.visibility = View.VISIBLE
                        emptyView.animate().alpha(1f).setDuration(250).start()
                    }.start()
                } else {
                    list.adapter = AppSelectionAdapter(apps) { app, checked ->
                        SettingsAppSelectionFragment.saveAppToggleState(requireContext(), app.packageName, checked)
                    }
                    loadingLayout.animate().alpha(0f).setDuration(200).withEndAction {
                        loadingLayout.visibility = View.GONE
                        list.visibility = View.VISIBLE
                        list.animate().alpha(1f).setDuration(300).start()
                    }.start()
                }
            }
        }

        view.findViewById<View>(R.id.btn_onboarding_finish).setOnClickListener {
            (activity as? OnboardingHost)?.finishOnboarding()
        }
    }
}

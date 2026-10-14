package com.example.kuwago

import android.graphics.Color
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.checkbox.MaterialCheckBox

class OnboardingGetStartedFragment : Fragment() {

    private var isTermsAccepted = false

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        i.inflate(R.layout.fragment_onboarding_get_started, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val btnGetStarted = view.findViewById<Button>(R.id.btn_get_started)
        val cbAgree = view.findViewById<MaterialCheckBox>(R.id.cb_onboarding_agree)
        val tvTerms = view.findViewById<TextView>(R.id.tv_terms_notice)
        val llTermsRow = view.findViewById<View>(R.id.ll_onboarding_terms_row)

        tvTerms?.text = Html.fromHtml(getString(R.string.onboarding_terms_notice), Html.FROM_HTML_MODE_LEGACY)

        fun updateGetStartedState(accepted: Boolean) {
            isTermsAccepted = accepted
            cbAgree?.isChecked = accepted
            if (accepted) {
                btnGetStarted?.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                btnGetStarted?.setTextColor(Color.BLACK)
            } else {
                btnGetStarted?.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#26FFFFFF"))
                btnGetStarted?.setTextColor(Color.parseColor("#66FFFFFF"))
            }
        }

        updateGetStartedState(false)

        fun showTermsPopup() {
            if (parentFragmentManager.findFragmentByTag("TermsBottomSheet") != null) return
            val termsDialog = TermsBottomSheetFragment.newInstance(requireAcceptance = true)
            termsDialog.onTermsAcceptedListener = {
                updateGetStartedState(true)
            }
            termsDialog.show(parentFragmentManager, "TermsBottomSheet")
        }

        // Show the terms and conditions popup at startup
        view.post {
            if (!isTermsAccepted && isAdded) {
                showTermsPopup()
            }
        }

        btnGetStarted?.setOnClickListener {
            if (isTermsAccepted && cbAgree?.isChecked == true) {
                (activity as? OnboardingHost)?.navigateToNextPage()
            } else {
                showTermsPopup()
            }
        }

        tvTerms?.setOnClickListener {
            showTermsPopup()
        }

        llTermsRow?.setOnClickListener {
            if (!isTermsAccepted) {
                showTermsPopup()
            } else {
                val newCheck = !(cbAgree?.isChecked ?: false)
                cbAgree?.isChecked = newCheck
                updateGetStartedState(newCheck)
            }
        }

        cbAgree?.setOnClickListener {
            if (!isTermsAccepted) {
                cbAgree.isChecked = false
                showTermsPopup()
            } else {
                updateGetStartedState(cbAgree.isChecked)
            }
        }
    }
}

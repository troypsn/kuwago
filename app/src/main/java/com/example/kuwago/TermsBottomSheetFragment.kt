package com.example.kuwago

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.checkbox.MaterialCheckBox

class TermsBottomSheetFragment : BottomSheetDialogFragment() {

    var onTermsAcceptedListener: (() -> Unit)? = null

    companion object {
        private const val ARG_REQUIRE_ACCEPTANCE = "arg_require_acceptance"

        fun newInstance(requireAcceptance: Boolean = false): TermsBottomSheetFragment {
            val fragment = TermsBottomSheetFragment()
            val args = Bundle()
            args.putBoolean(ARG_REQUIRE_ACCEPTANCE, requireAcceptance)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_terms_bottom_sheet, container, false)
    }

    override fun onStart() {
        super.onStart()
        dialog?.let { d ->
            val bottomSheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                sheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                sheet.background = ColorDrawable(Color.TRANSPARENT)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val requireAcceptance = arguments?.getBoolean(ARG_REQUIRE_ACCEPTANCE, false) ?: false
        val btnClose = view.findViewById<ImageView>(R.id.btn_close_terms)
        val btnAgree = view.findViewById<Button>(R.id.btn_agree_terms)
        val cbAgreeTerms = view.findViewById<MaterialCheckBox>(R.id.cb_agree_terms)
        val llCheckboxContainer = view.findViewById<View>(R.id.ll_terms_checkbox_container)

        btnClose?.setOnClickListener {
            dismiss()
        }

        if (requireAcceptance) {
            llCheckboxContainer?.visibility = View.VISIBLE
            btnAgree?.text = getString(R.string.terms_accept_button)
            btnAgree?.isEnabled = false
            btnAgree?.setBackgroundResource(R.drawable.bg_onboarding_disabled)
            btnAgree?.setTextColor(Color.parseColor("#66FFFFFF"))

            cbAgreeTerms?.setOnCheckedChangeListener { _, isChecked ->
                btnAgree?.isEnabled = isChecked
                if (isChecked) {
                    btnAgree?.setBackgroundResource(R.drawable.bg_onboarding_primary)
                    btnAgree?.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
                } else {
                    btnAgree?.setBackgroundResource(R.drawable.bg_onboarding_disabled)
                    btnAgree?.setTextColor(Color.parseColor("#66FFFFFF"))
                }
            }

            llCheckboxContainer?.setOnClickListener {
                cbAgreeTerms?.isChecked = !(cbAgreeTerms?.isChecked ?: false)
            }

            btnAgree?.setOnClickListener {
                if (cbAgreeTerms?.isChecked == true) {
                    onTermsAcceptedListener?.invoke()
                    dismiss()
                }
            }
        } else {
            llCheckboxContainer?.visibility = View.GONE
            btnAgree?.text = getString(R.string.terms_close_button)
            btnAgree?.isEnabled = true
            btnAgree?.setBackgroundResource(R.drawable.bg_onboarding_primary)
            btnAgree?.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
            btnAgree?.setOnClickListener {
                dismiss()
            }
        }
    }
}

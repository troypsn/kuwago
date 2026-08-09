package com.example.kuwago

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.fragment.app.Fragment

class SettingsGetSupportFragment : Fragment() {

    // Tracks which FAQ items are currently expanded
    private val expandedStates = mutableMapOf<Int, Boolean>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_settings_get_support, container, false)

        view.findViewById<ImageView>(R.id.btn_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // Set up all FAQ expandable rows
        setupFaqRow(view, R.id.faq_row_block_sender, R.id.faq_answer_block_sender, R.id.faq_arrow_block_sender)
        setupFaqRow(view, R.id.faq_row_how_blocking, R.id.faq_answer_how_blocking, R.id.faq_arrow_how_blocking)
        setupFaqRow(view, R.id.faq_row_blocks_visible, R.id.faq_answer_blocks_visible, R.id.faq_arrow_blocks_visible)

        setupFaqRow(view, R.id.faq_row_unblock, R.id.faq_answer_unblock, R.id.faq_arrow_unblock)
        setupFaqRow(view, R.id.faq_row_blocked_kept, R.id.faq_answer_blocked_kept, R.id.faq_arrow_blocked_kept)
        setupFaqRow(view, R.id.faq_row_manual_add, R.id.faq_answer_manual_add, R.id.faq_arrow_manual_add)

        setupFaqRow(view, R.id.faq_row_still_alerts, R.id.faq_answer_still_alerts, R.id.faq_arrow_still_alerts)
        setupFaqRow(view, R.id.faq_row_app_stopped, R.id.faq_answer_app_stopped, R.id.faq_arrow_app_stopped)
        setupFaqRow(view, R.id.faq_row_bg_permissions, R.id.faq_answer_bg_permissions, R.id.faq_arrow_bg_permissions)

        // Contact Developers — opens email
        view.findViewById<LinearLayout>(R.id.contact_developers_card).setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:kuwago.support@gmail.com")
                putExtra(Intent.EXTRA_SUBJECT, "Kuwago App — Support Request")
            }
            if (intent.resolveActivity(requireContext().packageManager) != null) {
                startActivity(intent)
            } else {
                startActivity(Intent.createChooser(intent, "Send email"))
            }
        }

        return view
    }

    private fun setupFaqRow(view: View, rowId: Int, answerId: Int, arrowId: Int) {
        val row = view.findViewById<LinearLayout>(rowId)
        val answer = view.findViewById<LinearLayout>(answerId)
        val arrow = view.findViewById<ImageView>(arrowId)

        expandedStates[rowId] = false

        // The clickable area is the first child LinearLayout (the header row)
        val header = row.getChildAt(0) as LinearLayout
        header.setOnClickListener {
            val isExpanded = expandedStates[rowId] ?: false
            if (isExpanded) {
                answer.visibility = View.GONE
                arrow.setImageResource(R.drawable.ic_chevron_down)
            } else {
                answer.visibility = View.VISIBLE
                arrow.setImageResource(R.drawable.ic_chevron_up)
            }
            expandedStates[rowId] = !isExpanded
        }
    }
}

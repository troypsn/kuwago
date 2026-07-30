package com.example.mykotlinapp

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// ──────────────────────────────────────────────
// Fragment
// ──────────────────────────────────────────────
class BlacklistFragment : Fragment() {

    // Views
    private lateinit var searchBox: EditText
    private lateinit var btnFilter: ImageView
    private lateinit var filterChipScroll: View
    private lateinit var switchAutoBlacklist: SwitchCompat
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout

    // Chips
    private lateinit var chipAll: TextView
    private lateinit var chipHighRisk: TextView
    private lateinit var chipMediumRisk: TextView
    private lateinit var chipUnknown: TextView
    private lateinit var chipAuto: TextView
    private lateinit var chipManual: TextView
    private lateinit var chipSortHighLow: TextView
    private lateinit var chipSortLowHigh: TextView
    private lateinit var chipSortNewest: TextView
    private lateinit var chipSortOldest: TextView

    // State
    private var filterChipsVisible = false
    private var activeFilterChip: TextView? = null   // risk/sender category chip
    private var activeSortChip: TextView? = null     // sort chip

    // Data
    private val allEntries = mutableListOf<BlacklistEntry>()
    private val displayedEntries = mutableListOf<BlacklistEntry>()
    private lateinit var adapter: BlacklistAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_blacklist, container, false)

        searchBox = view.findViewById(R.id.blacklist_search)
        btnFilter = view.findViewById(R.id.btn_filter)
        filterChipScroll = view.findViewById(R.id.filter_chip_scroll)
        switchAutoBlacklist = view.findViewById(R.id.switch_auto_blacklist)
        recyclerView = view.findViewById(R.id.blacklist_recycler_view)
        emptyState = view.findViewById(R.id.blacklist_empty_state)

        chipAll = view.findViewById(R.id.chip_all)
        chipHighRisk = view.findViewById(R.id.chip_high_risk)
        chipMediumRisk = view.findViewById(R.id.chip_medium_risk)
        chipUnknown = view.findViewById(R.id.chip_unknown)
        chipAuto = view.findViewById(R.id.chip_auto)
        chipManual = view.findViewById(R.id.chip_manual)
        chipSortHighLow = view.findViewById(R.id.chip_sort_high_low)
        chipSortLowHigh = view.findViewById(R.id.chip_sort_low_high)
        chipSortNewest = view.findViewById(R.id.chip_sort_newest)
        chipSortOldest = view.findViewById(R.id.chip_sort_oldest)

        setupRecyclerView()
        setupListeners()

        // Sync switch state with SharedPreferences
        val ctx = requireContext()
        switchAutoBlacklist.isChecked = BlacklistRepository.isAutoBlacklistEnabled(ctx)

        // Observe repository
        BlacklistRepository.blacklistLiveData.observe(viewLifecycleOwner) { list ->
            allEntries.clear()
            allEntries.addAll(list)
            applyFilters()
        }

        loadRealData()
        return view
    }

    override fun onResume() {
        super.onResume()
        loadRealData()
    }

    // ─── Setup ───────────────────────────────────

    private fun setupRecyclerView() {
        adapter = BlacklistAdapter(displayedEntries) { entry ->
            showRemoveConfirmationDialog(entry)
        }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }

    private fun loadRealData() {
        val ctx = context ?: return
        val list = BlacklistRepository.getBlacklist(ctx)
        allEntries.clear()
        allEntries.addAll(list)
        applyFilters()
    }

    private fun showRemoveConfirmationDialog(entry: BlacklistEntry) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle("Remove from Blacklist")
            .setMessage("Are you sure you want to remove \"${entry.sender}\" from your blacklist?\n\nThis will re-enable notification popups from this sender.")
            .setPositiveButton("Remove") { _, _ ->
                BlacklistRepository.removeEntry(ctx, entry.sender)
                Toast.makeText(ctx, "Removed ${entry.sender} from blacklist", Toast.LENGTH_SHORT).show()
                loadRealData()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Listeners ───────────────────────────────

    private fun setupListeners() {
        // Switch Auto Blacklist
        switchAutoBlacklist.setOnCheckedChangeListener { _, isChecked ->
            context?.let { ctx ->
                BlacklistRepository.setAutoBlacklistEnabled(ctx, isChecked)
                val statusText = if (isChecked) "Auto-blacklisting enabled" else "Auto-blacklisting disabled"
                Toast.makeText(ctx, statusText, Toast.LENGTH_SHORT).show()
            }
        }

        // Filter toggle button
        btnFilter.setOnClickListener {
            filterChipsVisible = !filterChipsVisible
            filterChipScroll.visibility = if (filterChipsVisible) View.VISIBLE else View.GONE
        }

        // Search
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilters()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Filter chips (category / sender type)
        val categoryChips = listOf(chipAll, chipHighRisk, chipMediumRisk, chipUnknown, chipAuto, chipManual)
        categoryChips.forEach { chip ->
            chip.setOnClickListener {
                if (activeFilterChip == chip) {
                    // Deselect — go back to All
                    setChipSelected(chip, false)
                    activeFilterChip = null
                    setChipSelected(chipAll, true)
                    activeFilterChip = chipAll
                } else {
                    activeFilterChip?.let { setChipSelected(it, false) }
                    setChipSelected(chip, true)
                    activeFilterChip = chip
                }
                applyFilters()
            }
        }

        // Sort chips
        val sortChips = listOf(chipSortHighLow, chipSortLowHigh, chipSortNewest, chipSortOldest)
        sortChips.forEach { chip ->
            chip.setOnClickListener {
                if (activeSortChip == chip) {
                    setChipSelected(chip, false)
                    activeSortChip = null
                } else {
                    activeSortChip?.let { setChipSelected(it, false) }
                    setChipSelected(chip, true)
                    activeSortChip = chip
                }
                applyFilters()
            }
        }

        // Default: "All" selected
        setChipSelected(chipAll, true)
        activeFilterChip = chipAll
    }

    // ─── Filter & Sort ───────────────────────────

    private fun applyFilters() {
        val query = searchBox.text.toString().trim().lowercase(Locale.getDefault())

        var result = allEntries.toList()

        // Text search
        if (query.isNotEmpty()) {
            result = result.filter { it.sender.lowercase(Locale.getDefault()).contains(query) }
        }

        // Category chip
        result = when (activeFilterChip) {
            chipHighRisk  -> result.filter { it.riskLevel == RiskLevel.HIGH }
            chipMediumRisk -> result.filter { it.riskLevel == RiskLevel.MEDIUM }
            chipUnknown   -> result.filter {
                // "Unknown" = sender looks like a raw phone number (starts with + or is all digits)
                it.sender.startsWith("+") || it.sender.all { c -> c.isDigit() || c == ' ' }
            }
            chipAuto      -> result.filter { it.method == BlacklistMethod.AUTO }
            chipManual    -> result.filter { it.method == BlacklistMethod.MANUAL }
            else          -> result // "All" or null
        }

        // Sort chip
        result = when (activeSortChip) {
            chipSortHighLow  -> result.sortedWith(compareBy { riskOrder(it.riskLevel) })
            chipSortLowHigh  -> result.sortedWith(compareByDescending { riskOrder(it.riskLevel) })
            chipSortNewest   -> result.sortedByDescending { it.timestamp }
            chipSortOldest   -> result.sortedBy { it.timestamp }
            else             -> result
        }

        displayedEntries.clear()
        displayedEntries.addAll(result)
        adapter.notifyDataSetChanged()

        emptyState.visibility = if (displayedEntries.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (displayedEntries.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun riskOrder(risk: RiskLevel): Int = when (risk) {
        RiskLevel.HIGH   -> 0
        RiskLevel.MEDIUM -> 1
        RiskLevel.LOW    -> 2
    }

    // ─── Chip UI ─────────────────────────────────

    private fun setChipSelected(chip: TextView, selected: Boolean) {
        val context = context ?: return
        if (selected) {
            chip.background = ContextCompat.getDrawable(context, R.drawable.bg_chip_selected)
            chip.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        } else {
            chip.background = ContextCompat.getDrawable(context, R.drawable.bg_chip_unselected)
            chip.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        }
    }
}

// ──────────────────────────────────────────────
// RecyclerView Adapter
// ──────────────────────────────────────────────
class BlacklistAdapter(
    private val items: List<BlacklistEntry>,
    private val onItemClick: (BlacklistEntry) -> Unit
) : RecyclerView.Adapter<BlacklistAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val senderText: TextView = view.findViewById(R.id.blacklist_item_sender)
        val riskBadge: TextView = view.findViewById(R.id.blacklist_item_risk_badge)
        val flaggedCount: TextView = view.findViewById(R.id.blacklist_item_flagged_count)
        val meta: TextView = view.findViewById(R.id.blacklist_item_meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_blacklist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context

        holder.senderText.text = item.sender

        val flagWord = if (item.flaggedCount == 1) "flagged message" else "flagged messages"
        holder.flaggedCount.text = "${item.flaggedCount} $flagWord"

        val methodLabel = when (item.method) {
            BlacklistMethod.AUTO   -> "Auto-blacklisted"
            BlacklistMethod.MANUAL -> "Manually added"
        }
        val dateLabel = formatTimestamp(item.timestamp)
        holder.meta.text = "$methodLabel · $dateLabel"

        // Risk badge
        val (badgeText, bgColor) = when (item.riskLevel) {
            RiskLevel.HIGH   -> Pair("High risk",   R.color.color_alert)
            RiskLevel.MEDIUM -> Pair("Medium risk", R.color.color_warning)
            RiskLevel.LOW    -> Pair("Low risk",    R.color.detection_green_stroke)
        }
        holder.riskBadge.text = badgeText
        holder.riskBadge.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(ctx, bgColor))
        holder.riskBadge.setTextColor(ContextCompat.getColor(ctx, R.color.white))

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount(): Int = items.size

    private fun formatTimestamp(ts: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ts }
        return when {
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) &&
                    now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> "Today"

            now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR) == 1 &&
                    now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> "Yesterday"

            else -> SimpleDateFormat("M/d/yy", Locale.getDefault()).format(then.time)
        }
    }
}

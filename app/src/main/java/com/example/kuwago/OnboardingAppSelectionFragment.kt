package com.example.kuwago
import android.content.Intent
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OnboardingAppSelectionFragment : Fragment() {
 override fun onCreateView(i: LayoutInflater,c: ViewGroup?,s: Bundle?)=i.inflate(R.layout.fragment_onboarding_app_selection,c,false)
 override fun onViewCreated(v:View,s:Bundle?){
  val list=v.findViewById<RecyclerView>(R.id.rv_onboarding_apps); list.layoutManager=LinearLayoutManager(requireContext())
  lifecycleScope.launch(Dispatchers.IO){ val c=requireContext(); val enabled=SettingsAppSelectionFragment.getSavedEnabledAppPackages(c); val pm=c.packageManager
   val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
   val apps=pm.queryIntentActivities(intent,0).filter { it.activityInfo.packageName != c.packageName && SettingsAppSelectionFragment.DEFAULT_MESSAGING_PACKAGES.contains(it.activityInfo.packageName) }.map { InstalledAppInfo(it.loadLabel(pm).toString(),it.activityInfo.packageName,it.loadIcon(pm),enabled.contains(it.activityInfo.packageName)) }.sortedBy{it.appName.lowercase()}
   withContext(Dispatchers.Main){if(isAdded) list.adapter=AppSelectionAdapter(apps){app,checked->SettingsAppSelectionFragment.saveAppToggleState(requireContext(),app.packageName,checked)}}
  }
  v.findViewById<View>(R.id.btn_onboarding_finish).setOnClickListener{(activity as? OnboardingHost)?.finishOnboarding()}
 }
}

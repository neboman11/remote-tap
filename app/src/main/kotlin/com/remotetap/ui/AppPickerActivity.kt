package com.remotetap.ui

import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Process
import android.os.UserManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.remotetap.R
import com.remotetap.databinding.ActivityAppPickerBinding
import com.remotetap.repository.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppPickerBinding
    private lateinit var prefs: PreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PreferencesRepository(this)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { loadAppsAcrossProfiles() }
            binding.recyclerView.adapter = AppAdapter(apps) { packageName, userSerial ->
                prefs.watchedPackageName = packageName
                prefs.watchedUserSerial = userSerial
                finish()
            }
        }
    }

    private fun loadAppsAcrossProfiles(): List<AppInfo> {
        val launcherApps = getSystemService(LauncherApps::class.java)
        val userManager = getSystemService(UserManager::class.java)
        val density = resources.displayMetrics.densityDpi
        val myUserHandle = Process.myUserHandle()

        return launcherApps.profiles.flatMap { profile ->
            val isWork = profile != myUserHandle
            val userSerial = userManager.getSerialNumberForUser(profile)
            launcherApps.getActivityList(null, profile).map { info ->
                AppInfo(
                    packageName = info.applicationInfo.packageName,
                    userSerial = userSerial,
                    label = info.label.toString(),
                    icon = info.getIcon(density),
                    isWork = isWork
                )
            }
        }.sortedWith(compareBy({ it.label.lowercase() }, { it.isWork }))
    }

    private data class AppInfo(
        val packageName: String,
        val userSerial: Long,
        val label: String,
        val icon: Drawable,
        val isWork: Boolean
    )

    private class AppAdapter(
        private val apps: List<AppInfo>,
        private val onPick: (packageName: String, userSerial: Long) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.iv_app_icon)
            val label: TextView = view.findViewById(R.id.tv_app_name)
            val workBadge: TextView = view.findViewById(R.id.tv_work_badge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.icon.setImageDrawable(app.icon)
            holder.label.text = app.label
            holder.workBadge.visibility = if (app.isWork) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener { onPick(app.packageName, app.userSerial) }
        }

        override fun getItemCount() = apps.size
    }
}

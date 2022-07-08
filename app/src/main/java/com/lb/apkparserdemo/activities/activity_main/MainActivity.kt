package com.lb.apkparserdemo.activities.activity_main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.*
import androidx.core.view.*
import androidx.lifecycle.ViewModelProvider
import com.lb.apkparserdemo.R
import com.lb.apkparserdemo.databinding.ActivityMainBinding

class MainActivity : BoundActivity<ActivityMainBinding>(ActivityMainBinding::inflate) {
    private lateinit var viewModel: MainActivityViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[MainActivityViewModel::class.java]
        if (savedInstanceState == null) {
            viewModel.init()
        }
        viewModel.appsHandledLiveData.observe(this) {
            binding.appsCountTextView.text = it.toString()
        }
        viewModel.apkFilesHandledLiveData.observe(this) {
            binding.apksCountTextView.text = it.toString()
        }
        viewModel.frameworkErrorsOfApkTypeLiveData.observe(this) {
            binding.frameworkErrorsGettingApkTypeTextView.text = it.toString()
        }
        viewModel.parsingErrorsLiveData.observe(this) {
            binding.parsingErrorsTextView.text = it.toString()
        }
        viewModel.wrongApkTypeErrorsLiveData.observe(this) {
            binding.apkTypeDetectionErrorsTextView.text = it.toString()
        }
        viewModel.wrongPackageNameErrorsLiveData.observe(this) {
            binding.wrongPackageNameErrorsTextView.text = it.toString()
        }
        viewModel.failedGettingAppIconErrorsLiveData.observe(this) {
            binding.iconFetchingErrorsTextView.text = it.toString()
        }
        viewModel.wrongLabelErrorsLiveData.observe(this) {
            binding.wrongLabelErrorsTextView.text = it.toString()
        }
        viewModel.wrongVersionCodeErrorsLiveData.observe(this) {
            binding.wrongVersionCodeErrorsTextView.text = it.toString()
        }
        viewModel.wrongVersionNameErrorsLiveData.observe(this) {
            binding.wrongVersionNameErrorsTextView.text = it.toString()
        }
        viewModel.systemAppsErrorsCountLiveData.observe(this) {
            binding.systemAppsErrorsTextView.text = it.toString()
        }
        viewModel.isDoneLiveData.observe(this) { isDone ->
            binding.summaryTextView.isVisible = isDone
            if (!isDone) {
                binding.summaryNoteTextView.isVisible = false
                return@observe
            }
            val isSystemAppCountAllWeGot =
                viewModel.systemAppsErrorsCountLiveData.value == (viewModel.wrongVersionNameErrorsLiveData.value + viewModel.wrongVersionCodeErrorsLiveData.value
                        + viewModel.wrongLabelErrorsLiveData.value + viewModel.failedGettingAppIconErrorsLiveData.value + viewModel.wrongPackageNameErrorsLiveData.value
                        + viewModel.wrongApkTypeErrorsLiveData.value + viewModel.parsingErrorsLiveData.value)
            binding.summaryNoteTextView.isVisible = isSystemAppCountAllWeGot
        }
        addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.main, menu)
            }

            override fun onMenuItemSelected(item: MenuItem): Boolean {
                var url: String? = null
                when (item.itemId) {
                    R.id.menuItem_all_my_apps -> url =
                        "https://play.google.com/store/apps/developer?id=AndroidDeveloperLB"
                    R.id.menuItem_all_my_repositories -> url =
                        "https://github.com/AndroidDeveloperLB"
                    R.id.menuItem_current_repository_website -> url =
                        "https://github.com/AndroidDeveloperLB/apk-parser"
                }
                if (url == null)
                    return true
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                @Suppress("DEPRECATION")
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                startActivity(intent)
                return true
            }
        })
    }

}

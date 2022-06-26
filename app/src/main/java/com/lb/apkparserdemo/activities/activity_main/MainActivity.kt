package com.lb.apkparserdemo.activities.activity_main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.*
import androidx.core.view.isVisible
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
            binding.appsCountTextView.text = getString(R.string.apps_analyzed, it)
        }
        viewModel.apkFilesHandledLiveData.observe(this) {
            binding.apksCountTextView.text = getString(R.string.apks_analyzed, it)
        }
        viewModel.frameworkErrorsOfApkTypeLiveData.observe(this) {
            binding.frameworkErrorsGettingApkTypeTextView.text =
                getString(R.string.framework_errors_getting_apk_type, it)
        }
        viewModel.parsingErrorsLiveData.observe(this) {
            binding.parsingErrorsTextView.text = getString(R.string.parsing_errors, it)
        }
        viewModel.wrongApkTypeErrorsLiveData.observe(this) {
            binding.apkTypeDetectionErrorsTextView.text =
                getString(R.string.apk_type_detection_errors, it)
        }
        viewModel.wrongPackageNameErrorsLiveData.observe(this) {
            binding.wrongPackageNameErrorsTextView.text =
                getString(R.string.wrong_package_name_errors, it)
        }
        viewModel.failedGettingAppIconErrorsLiveData.observe(this) {
            binding.iconFetchingErrorsTextView.text = getString(R.string.icon_fetching_errors, it)
        }
        viewModel.wrongLabelErrorsLiveData.observe(this) {
            binding.wrongLabelErrorsTextView.text = getString(R.string.wrong_label_errors, it)
        }
        viewModel.wrongVersionCodeErrorsLiveData.observe(this) {
            binding.wrongVersionCodeErrorsTextView.text =
                getString(R.string.wrong_version_code_errors, it)
        }
        viewModel.wrongVersionNameErrorsLiveData.observe(this) {
            binding.wrongVersionNameErrorsTextView.text =
                getString(R.string.wrong_version_name_errors, it)
        }
        viewModel.systemAppsErrorsCountLiveData.observe(this) {
            binding.systemAppsErrorsTextView.text = getString(R.string.system_apps_errors, it)
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
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        var url: String? = null
        when (item.itemId) {
            R.id.menuItem_all_my_apps -> url =
                "https://play.google.com/store/apps/developer?id=AndroidDeveloperLB"
            R.id.menuItem_all_my_repositories -> url = "https://github.com/AndroidDeveloperLB"
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


}

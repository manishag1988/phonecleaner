package com.phonecleaner

import android.Manifest
import android.annotation.TargetApi
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.phonecleaner.adapter.MediaAdapter
import com.phonecleaner.databinding.ActivityMainBinding
import com.phonecleaner.model.MediaFile
import com.phonecleaner.utils.FileScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var fileScanner: FileScanner
    private var currentFiles: List<MediaFile> = emptyList()
    private var pendingDeleteMessage: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.getOrDefault(Manifest.permission.READ_MEDIA_IMAGES, false) &&
                    permissions.getOrDefault(Manifest.permission.READ_MEDIA_VIDEO, false)
        } else {
            permissions.getOrDefault(Manifest.permission.READ_EXTERNAL_STORAGE, false)
        }
        if (granted) {
            loadFiles("all")
        } else {
            Toast.makeText(this, "Storage permission required", Toast.LENGTH_LONG).show()
        }
    }

    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, pendingDeleteMessage ?: "Deleted", Toast.LENGTH_SHORT).show()
            reloadCurrentFiles()
        } else {
            Toast.makeText(this, "Delete canceled", Toast.LENGTH_SHORT).show()
        }
        pendingDeleteMessage = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.setHasFixedSize(true)

        fileScanner = FileScanner(contentResolver)

        setupTabs()

        checkPermissionsAndLoad()

        binding.buttonDeleteSelected.setOnClickListener {
            val adapter = binding.recyclerView.adapter as? MediaAdapter
            if (adapter != null && adapter.hasSelection()) {
                deleteSelectedFiles()
            } else {
                if (currentFiles.isNotEmpty()) {
                    adapter?.toggleSelectionMode()
                    updateSelectionUI(adapter)
                }
            }
        }

        binding.buttonDeleteSelected.setOnLongClickListener {
            val adapter = binding.recyclerView.adapter as? MediaAdapter
            if (adapter != null && currentFiles.isNotEmpty()) {
                adapter.selectAll()
                updateSelectionUI(adapter)
                true
            } else {
                false
            }
        }
    }

    private fun setupTabs() {
        val tabs = listOf("All", "GIFs", "Photos", "Videos")
        tabs.forEach { title ->
            binding.tabLayout.addTab(binding.tabLayout.newTab().setText(title))
        }
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                exitSelectionMode()
                when (tab?.text) {
                    "All" -> loadFiles("all")
                    "GIFs" -> loadFiles("gifs")
                    "Photos" -> loadFiles("photos")
                    "Videos" -> loadFiles("videos")
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun updateSelectionUI(adapter: MediaAdapter?) {
        binding.buttonDeleteSelected.isEnabled = adapter != null && currentFiles.isNotEmpty()

        if (adapter != null && adapter.isSelectionMode) {
            binding.buttonDeleteSelected.text = if (adapter.hasSelection()) {
                "Delete (${adapter.getSelectedFiles().size})"
            } else {
                "Cancel"
            }
        } else {
            binding.buttonDeleteSelected.text = "Select"
        }
    }

    private fun exitSelectionMode() {
        (binding.recyclerView.adapter as? MediaAdapter)?.let {
            if (it.isSelectionMode) {
                it.clearSelection()
                it.toggleSelectionMode()
                updateSelectionUI(it)
            }
        }
    }

    private fun checkPermissionsAndLoad() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }

        val allGranted = permissions.all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            loadFiles("all")
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private fun loadFiles(filter: String) {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
            binding.textViewEmpty.visibility = View.GONE

            val files = withContext(Dispatchers.IO) {
                when (filter) {
                    "gifs" -> fileScanner.scanGifs()
                    else -> fileScanner.scanAllFiles().let { all ->
                        when (filter) {
                            "photos" -> all.filter { !it.isGif && !it.mimeType.startsWith("video") }
                            "videos" -> all.filter { it.mimeType.startsWith("video") }
                            else -> all
                        }
                    }
                }
            }

            currentFiles = files
            binding.progressBar.visibility = View.GONE

            if (files.isEmpty()) {
                binding.textViewEmpty.visibility = View.VISIBLE
                binding.recyclerView.visibility = View.GONE
            } else {
                binding.textViewEmpty.visibility = View.GONE
                binding.recyclerView.visibility = View.VISIBLE
                val adapter = MediaAdapter(files, contentResolver, { file ->
                    deleteFile(file)
                }, { file ->
                    previewFile(file)
                }) { hasSelection ->
                    val currentAdapter = binding.recyclerView.adapter as? MediaAdapter
                    if (currentAdapter != null) {
                        updateSelectionUI(currentAdapter)
                    } else {
                        binding.buttonDeleteSelected.isEnabled = false
                        binding.buttonDeleteSelected.text = "Select"
                    }
                }
                binding.recyclerView.adapter = adapter
                updateSelectionUI(adapter)
            }
        }
    }

    private fun previewFile(file: MediaFile) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(file.uri, file.mimeType.ifBlank { "application/octet-stream" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(Intent.createChooser(intent, "Preview file"))
        } catch (_: Exception) {
            Toast.makeText(this, "No app available to preview this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun reloadCurrentFiles() {
        val currentTab = binding.tabLayout.selectedTabPosition
        val filter = when (currentTab) {
            1 -> "gifs"
            2 -> "photos"
            3 -> "videos"
            else -> "all"
        }
        loadFiles(filter)
    }

    private fun deleteFile(file: MediaFile) {
        deleteFiles(listOf(file), "Deleted: ${file.displayName}")
    }

    private fun deleteSelectedFiles() {
        val adapter = binding.recyclerView.adapter as? MediaAdapter ?: return
        val selected = adapter.getSelectedFiles()
        if (selected.isEmpty()) return

        deleteFiles(selected, "Deleted ${selected.size} file(s)")
    }

    @TargetApi(Build.VERSION_CODES.R)
    private fun deleteFiles(filesToDelete: List<MediaFile>, successMessage: String) {
        if (filesToDelete.isEmpty()) return

        pendingDeleteMessage = successMessage

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val deleteRequest = MediaStore.createDeleteRequest(contentResolver, filesToDelete.map { it.uri })
            val senderRequest = IntentSenderRequest.Builder(deleteRequest.intentSender).build()
            deleteRequestLauncher.launch(senderRequest)
            return
        }

        lifecycleScope.launch {
            var deletedCount = 0
            withContext(Dispatchers.IO) {
                filesToDelete.forEach { file ->
                    try {
                        if (contentResolver.delete(file.uri, null, null) > 0) {
                            deletedCount++
                        }
                    } catch (_: Exception) {
                        // failed
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (deletedCount > 0) {
                    Toast.makeText(this@MainActivity, successMessage, Toast.LENGTH_SHORT).show()
                    reloadCurrentFiles()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to delete", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

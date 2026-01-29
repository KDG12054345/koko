package com.faust.presentation.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.faust.R
import com.faust.data.utils.PreferenceManager
import com.faust.models.BlockedApp
import com.faust.presentation.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * 메인 Fragment (기존 MainActivity의 내용)
 * 차단 앱 목록 및 포인트 표시
 */
class MainFragment : Fragment() {
    private val viewModel: MainViewModel by viewModels()
    private val preferenceManager: PreferenceManager by lazy {
        PreferenceManager(requireContext())
    }
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: BlockedAppAdapter
    private lateinit var textCurrentPoints: TextView
    private lateinit var buttonAddApp: Button
    private lateinit var buttonPersona: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        textCurrentPoints = view.findViewById(R.id.textCurrentPoints)
        buttonAddApp = view.findViewById(R.id.buttonAddApp)
        buttonPersona = view.findViewById(R.id.buttonPersona)

        setupRecyclerView()
        setupViews()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        recyclerView = view?.findViewById(R.id.recyclerViewBlockedApps) ?: return
        adapter = BlockedAppAdapter { blockedApp ->
            removeBlockedApp(blockedApp)
        }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }

    private fun setupViews() {
        buttonAddApp.setOnClickListener {
            showAddAppDialog()
        }

        buttonPersona.setOnClickListener {
            showPersonaDialog()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.currentPoints.collect { points ->
                updatePointsDisplay(points)
            }
        }

        lifecycleScope.launch {
            viewModel.blockedApps.collect { apps ->
                if (::adapter.isInitialized) {
                    adapter.submitList(apps)
                }
            }
        }
    }

    private fun updatePointsDisplay(points: Int) {
        textCurrentPoints.text = getString(R.string.current_points, points)
    }

    private fun showAddAppDialog() {
        lifecycleScope.launch {
            val maxApps = viewModel.getMaxBlockedApps()
            val currentApps = viewModel.blockedApps.value.size

            if (currentApps >= maxApps) {
                android.widget.Toast.makeText(
                    requireContext(),
                    getString(R.string.max_apps_limit, maxApps),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val dialog = AppSelectionDialog { app ->
                lifecycleScope.launch {
                    val success = viewModel.addBlockedApp(app)
                    if (success) {
                        android.widget.Toast.makeText(
                            requireContext(),
                            getString(R.string.app_added, app.appName),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        android.widget.Toast.makeText(
                            requireContext(),
                            getString(R.string.add_failed),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            dialog.show(childFragmentManager, "AppSelectionDialog")
        }
    }

    private fun removeBlockedApp(blockedApp: com.faust.models.BlockedApp) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.remove_app_title)
            .setMessage(getString(R.string.remove_app_message, blockedApp.appName))
            .setPositiveButton(R.string.remove) { _, _ ->
                lifecycleScope.launch {
                    viewModel.removeBlockedApp(blockedApp)
                    android.widget.Toast.makeText(
                        requireContext(),
                        getString(R.string.app_removed, blockedApp.appName),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPersonaDialog() {
        val dialog = PersonaSelectionDialog(preferenceManager) { personaTypeString ->
            if (personaTypeString != null) {
                preferenceManager.setPersonaType(personaTypeString)
                android.widget.Toast.makeText(
                    requireContext(),
                    getString(R.string.persona_selected, personaTypeString),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                preferenceManager.setPersonaType("")
                android.widget.Toast.makeText(
                    requireContext(),
                    getString(R.string.persona_unregister),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
        dialog.show(childFragmentManager, "PersonaSelectionDialog")
    }
}

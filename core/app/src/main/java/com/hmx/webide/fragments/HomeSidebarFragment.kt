package com.hmx.webide.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.hmx.webide.activities.MainActivity
import com.hmx.webide.activities.NewProjectActivity
import com.hmx.webide.adapters.RecentProjectsAdapter
import com.hmx.webide.databinding.FragmentHomeSidebarBinding
import com.hmx.webide.preferences.internal.GeneralPreferences
import com.hmx.webide.resources.R.string
import com.hmx.webide.utils.Environment
import com.hmx.webide.utils.ProjectValidator
import com.hmx.webide.utils.flashError
import java.io.File

class HomeSidebarFragment : Fragment() {

  private var _binding: FragmentHomeSidebarBinding? = null
  private val binding get() = checkNotNull(_binding)

  private val adapter by lazy { RecentProjectsAdapter(::openProject) }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    _binding = FragmentHomeSidebarBinding.inflate(inflater, container, false)
    return binding.root
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    binding.projectList.layoutManager = LinearLayoutManager(requireContext())
    binding.projectList.adapter = adapter

    binding.createProjectBtn.setOnClickListener { showCreateProject() }

    binding.profileArea.setOnClickListener {
      ProfileSheet().show(childFragmentManager, ProfileSheet.TAG)
    }

    binding.searchInput.addTextChangedListener(object : android.text.TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        refreshList(s?.toString().orEmpty())
      }

      override fun afterTextChanged(s: android.text.Editable?) {}
    })

    refreshList("")
  }

  private fun refreshList(query: String) {
    val recent =
      GeneralPreferences.recentProjects
        .filter { it.isNotBlank() && File(it).exists() }
        .toSet()
    val scanned = scanProjectsDir().filter { matches(it, query) }
    val list = (recent + scanned).distinct()
    adapter.submit(list.map { it to (it in recent) })
  }

  private fun scanProjectsDir(): List<String> {
    val dir = Environment.PROJECTS_DIR
    if (!dir.isDirectory) return emptyList()
    return dir
      .listFiles()
      ?.filter { it.isDirectory && ProjectValidator.isSupportedProject(it) }
      ?.map { it.absolutePath }
      ?.sortedBy { it.lowercase() }
      .orEmpty()
  }

  private fun matches(path: String, query: String): Boolean {
    if (query.isBlank()) return true
    val lower = query.lowercase()
    val file = File(path)
    return file.name.lowercase().contains(lower) || path.lowercase().contains(lower)
  }

  private fun openProject(root: File) {
    if (!ProjectValidator.isSupportedProject(root)) {
      flashError(getString(string.msg_unsupported_project))
      return
    }
    GeneralPreferences.addRecentProject(root.absolutePath)
    (requireActivity() as MainActivity).openProject(root)
  }

  private fun showCreateProject() {
    startActivity(Intent(requireContext(), NewProjectActivity::class.java))
  }

  override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
  }
}

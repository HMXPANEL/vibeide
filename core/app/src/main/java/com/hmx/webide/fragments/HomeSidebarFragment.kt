package com.hmx.webide.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.hmx.webide.activities.MainActivity
import com.hmx.webide.adapters.RecentProjectsAdapter
import com.hmx.webide.databinding.FragmentHomeSidebarBinding
import com.hmx.webide.preferences.databinding.LayoutDialogTextInputBinding
import com.hmx.webide.preferences.internal.GeneralPreferences
import com.hmx.webide.resources.R.string
import com.hmx.webide.utils.DialogUtils
import com.hmx.webide.utils.Environment
import com.hmx.webide.utils.ProjectValidator
import com.hmx.webide.utils.flashError
import com.hmx.webide.web.WebProjectTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val builder = DialogUtils.newMaterialDialogBuilder(requireContext())
    val input = LayoutDialogTextInputBinding.inflate(layoutInflater)
    input.name.setHint(string.project_name)

    builder.setView(input.root)
    builder.setTitle(string.title_create_project)
    builder.setCancelable(false)
    builder.setPositiveButton(string.create) { dialog, _ ->
      dialog.dismiss()
      val name = input.name.editText?.text?.toString()?.trim().orEmpty()
      if (name.isBlank()) {
        flashError(string.msg_empty_project_name)
        return@setPositiveButton
      }
      val dir = File(Environment.PROJECTS_DIR, name)
      if (dir.exists()) {
        flashError(string.msg_project_already_exists)
        return@setPositiveButton
      }
      showTemplateChooser(name)
    }
    builder.setNegativeButton(android.R.string.cancel, null)
    builder.show()
  }

  private fun showTemplateChooser(name: String) {
    val templates = WebProjectTemplates.all
    DialogUtils.newMaterialDialogBuilder(requireContext())
      .setTitle(string.title_choose_template)
      .setItems(templates.map { it.name }.toTypedArray()) { dialog, which ->
        dialog.dismiss()
        doCreateProject(name, templates[which])
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun doCreateProject(name: String, template: WebProjectTemplates.Template) {
    val dir = File(Environment.PROJECTS_DIR, name)
    lifecycleScope.launch(Dispatchers.IO) {
      runCatching {
        dir.mkdirs()
        template.files(name).forEach { (path, content) ->
          val file = File(dir, path)
          file.parentFile?.mkdirs()
          file.writeText(content)
        }
      }.onFailure { flashError(string.msg_project_create_failed) }

      withContext(Dispatchers.Main) {
        GeneralPreferences.addRecentProject(dir.absolutePath)
        openProject(dir)
      }
    }
  }

  override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
  }
}

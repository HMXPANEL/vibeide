package com.hmx.ide.knowledge

import com.hmx.ide.knowledge.model.FileModel
import java.io.File

object FileParser {

  fun parse(file: File, root: File, content: String): FileModel {
    return FileModel(
      path = file.absolutePath,
      relativePath = file.relativeTo(root).path,
    )
  }
}
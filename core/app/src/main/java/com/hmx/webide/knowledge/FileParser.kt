package com.hmx.webide.knowledge

import com.hmx.webide.knowledge.model.FileModel
import java.io.File

object FileParser {

  fun parse(file: File, root: File, content: String): FileModel {
    return FileModel(
      path = file.absolutePath,
      relativePath = file.relativeTo(root).path,
    )
  }
}
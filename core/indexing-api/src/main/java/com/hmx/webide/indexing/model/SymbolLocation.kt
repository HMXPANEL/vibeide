package com.hmx.webide.indexing.model

data class SymbolLocation(
  val filePath: String,
  val line: Int,
  val column: Int,
)

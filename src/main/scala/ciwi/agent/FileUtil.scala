package ciwi.agent

import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}

object FileUtil {
  def deleteRecursively(path: Path): Unit = {
    if (!Files.exists(path)) return
    Files.walkFileTree(path, new SimpleFileVisitor[Path]() {
      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
        Files.deleteIfExists(file)
        FileVisitResult.CONTINUE
      }
      override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
        Files.deleteIfExists(dir)
        FileVisitResult.CONTINUE
      }
    })
  }
}

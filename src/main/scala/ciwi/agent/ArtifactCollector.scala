package ciwi.agent

import java.nio.file.{FileSystems, Files, Path, Paths}
import java.util.Base64
import scala.collection.mutable
import scala.jdk.CollectionConverters._

object ArtifactCollector {
  private val maxArtifactsPerJob = 200
  private val maxArtifactFileBytes = 20L * 1024L * 1024L

  def collect(execDir: Path, globs: List[String]): (List[UploadArtifact], String) = {
    if (globs.isEmpty) return (Nil, "")

    val matchers = globs
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(_.replace('\\', '/'))
      .flatMap { pattern =>
        val collapsed = pattern.replace("/**/", "/")
        if (collapsed != pattern) List(pattern, collapsed) else List(pattern)
      }
      .distinct
      .map(pattern => FileSystems.getDefault.getPathMatcher("glob:" + pattern))

    val found = mutable.LinkedHashSet[String]()
    Files.walk(execDir).iterator().asScala.foreach { p =>
      if (Files.isRegularFile(p)) {
        val rel = execDir.relativize(p).toString.replace('\\', '/')
        if (matchers.exists(_.matches(Paths.get(rel)))) found += rel
      }
    }

    val sb = new StringBuilder
    sb.append("[artifacts] globs=").append(globs.mkString(", ")).append("\n")

    val artifacts = found.toList.sorted.take(maxArtifactsPerJob).flatMap { rel =>
      val file = execDir.resolve(rel)
      val size = Files.size(file)
      if (size > maxArtifactFileBytes) {
        sb.append(s"[artifacts] skip=$rel reason=size($size>$maxArtifactFileBytes)\n")
        None
      } else {
        val bytes = Files.readAllBytes(file)
        sb.append(s"[artifacts] include=$rel size=${bytes.length}\n")
        Some(UploadArtifact(rel, Base64.getEncoder.encodeToString(bytes)))
      }
    }

    if (artifacts.isEmpty) sb.append("[artifacts] none\n")
    (artifacts, sb.toString)
  }
}

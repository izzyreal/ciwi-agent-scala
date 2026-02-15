package ciwi.agent

import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable

object DepArtifacts {
  def dependencyJobIds(env: Map[String, String]): List[String] = {
    val ids = mutable.LinkedHashSet[String]()
    env.get("CIWI_DEP_ARTIFACT_JOB_IDS").toList.flatMap(_.split(",").toList).map(_.trim).filter(_.nonEmpty).foreach(ids += _)
    env.get("CIWI_DEP_ARTIFACT_JOB_ID").map(_.trim).filter(_.nonEmpty).foreach(ids += _)
    ids.toList
  }

  def restore(api: ApiClient, env: Map[String, String], execDir: Path): Either[String, String] = {
    val ids = dependencyJobIds(env)
    if (ids.isEmpty) return Right("")
    val sb = new StringBuilder
    sb.append("[dep-artifacts] source_jobs=").append(ids.mkString(",")).append('\n')

    var failure: Option[String] = None

    for (id <- ids if failure.isEmpty) {
      api.listArtifacts(id) match {
        case Left(err) =>
          failure = Some(s"list dependency artifacts failed for $id: $err")
        case Right(entries) =>
          for (e <- entries if failure.isEmpty) {
            val rel = e.path.replace('\\', '/')
            if (rel.startsWith("/") || rel.contains("..")) {
              sb.append(s"[dep-artifacts] skip=$rel reason=unsafe_path\n")
            } else {
              api.downloadArtifact(e.url) match {
                case Left(err) =>
                  failure = Some(s"download dependency artifact failed for $rel: $err")
                case Right(bytes) =>
                  val target = execDir.resolve(Paths.get(rel))
                  Files.createDirectories(target.getParent)
                  Files.write(target, bytes)
                  sb.append(s"[dep-artifacts] restored=$rel bytes=${bytes.length}\n")
              }
            }
          }
      }
    }

    failure match {
      case Some(err) => Left(err)
      case None => Right(sb.toString)
    }
  }

}

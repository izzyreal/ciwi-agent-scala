package ciwi.agent

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

trait CheckoutRunner {
  def checkout(source: SourceSpec, targetDir: Path): Either[String, String]
}

object GitCheckoutRunner extends CheckoutRunner {
  override def checkout(source: SourceSpec, targetDir: Path): Either[String, String] = {
    def runCapture(cmd: List[String]): (Int, String) = {
      val pb = new ProcessBuilder(cmd.asJava)
      pb.redirectErrorStream(true)
      val proc = pb.start()
      val text = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      val exit = proc.waitFor()
      (exit, text)
    }

    val out = new StringBuilder
    try {
      Files.createDirectories(targetDir.getParent)
      val cloneCmd = List("git", "clone", "--depth", "1", source.repo, targetDir.toString)
      val (cloneExit, cloneOut) = runCapture(cloneCmd)
      out.append(cloneOut)
      if (cloneExit != 0) return Left(out.toString.trim)

      val ref = source.ref.map(_.trim).filter(_.nonEmpty)
      ref match {
        case None => Right(out.toString)
        case Some(r) =>
          val fetchCmd = List("git", "-C", targetDir.toString, "fetch", "--depth", "1", "origin", r)
          val (fetchExit, fetchOut) = runCapture(fetchCmd)
          out.append(fetchOut)
          if (fetchExit != 0) return Left(out.toString.trim)

          val checkoutCmd = List("git", "-C", targetDir.toString, "checkout", "--force", "FETCH_HEAD")
          val (checkoutExit, checkoutOut) = runCapture(checkoutCmd)
          out.append(checkoutOut)
          if (checkoutExit != 0) return Left(out.toString.trim)

          Right(out.toString)
      }
    } catch {
      case NonFatal(e) =>
        out.append(e.getMessage)
        Left(out.toString.trim)
    }
  }
}

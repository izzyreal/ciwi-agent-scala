package ciwi.agent

object Main {
  def main(args: Array[String]): Unit = {
    val code = CiwiAgent.runLoop()
    sys.exit(code)
  }
}

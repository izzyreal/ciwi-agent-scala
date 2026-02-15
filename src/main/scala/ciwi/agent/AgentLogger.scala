package ciwi.agent

trait AgentLogger {
  def info(message: String): Unit
  def warn(message: String): Unit
  def error(message: String): Unit
}

object StdoutAgentLogger extends AgentLogger {
  override def info(message: String): Unit = println(message)
  override def warn(message: String): Unit = println(message)
  override def error(message: String): Unit = println(message)
}

package ch.epfl.bluebrain.nexus.storage

import journal.Logger

import scala.sys.process.ProcessLogger

/**
  * Simple [[scala.sys.process.ProcessLogger]] implementation backed by a [[StringBuilder]].
  *
  * @param cmd the process name, used for logging purposes
  * @note This expects a brief, single-line output.
  */
class StringProcessLogger(cmd: String) extends ProcessLogger {

  private val logger = Logger(cmd)

  private val builder = new StringBuilder

  override def out(s: => String): Unit = {
    builder.append(s)
    logger.debug(s)
  }

  override def err(s: => String): Unit = {
    builder.append(s)
    logger.error(s)
  }

  override def buffer[T](f: => T): T = f

  override def toString: String = builder.toString
}

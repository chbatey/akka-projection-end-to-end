package akka.projection.testing

import akka.actor.typed.ActorSystem
import com.typesafe.config.{Config, ConfigFactory}

object Main {

  def main(args: Array[String]): Unit = {
    args.headOption match {

      case Some(portString) if portString.matches("""\d+""") =>
        val port = portString.toInt
        val httpPort = ("80" + portString.takeRight(2)).toInt
        val prometheusPort = ("900" + portString.takeRight(1)).toInt
        startNode(port, httpPort, prometheusPort)

      case None =>
        throw new IllegalArgumentException("port number required argument")
    }
  }

  def startNode(port: Int, httpPort: Int, prometheusPort: Int): Unit = {
    ActorSystem[String](Guardian(), "test", config(port, httpPort, prometheusPort))

  }

  def config(port: Int, httpPort: Int, prometheusPort: Int): Config =
    ConfigFactory.parseString(
      s"""
      akka.remote.artery.canonical.port = $port
      test.http.port = $httpPort
      cinnamon.prometheus.http-server.port = $prometheusPort
       """).withFallback(ConfigFactory.load())

}

package akka.projection.testing

import akka.actor.typed.ActorSystem
import akka.projection.eventsourced.EventEnvelope
import akka.projection.jdbc.scaladsl.JdbcHandler
import org.slf4j.{Logger, LoggerFactory}

class ProjectionHandler(tag: String, system: ActorSystem[_])
    extends JdbcHandler[EventEnvelope[ConfigurablePersistentActor.Event], HikariJdbcSession] {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  override def process(session: HikariJdbcSession, envelope: EventEnvelope[ConfigurablePersistentActor.Event]): Unit = {
    log.info("Event {} for tag {} test {}", envelope.event.payload, tag, envelope.event.testName)
    session.withConnection { connection =>
      require(!connection.getAutoCommit)
      connection.createStatement()
        .execute(s"insert into events(name, event) values ('${envelope.event.testName}','${envelope.event.payload}')")
    }
  }
}
class GroupedProjectionHandler(tag: String, system: ActorSystem[_])
  extends JdbcHandler[Seq[EventEnvelope[ConfigurablePersistentActor.Event]], HikariJdbcSession] {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  override def process(session: HikariJdbcSession, envelopes: Seq[EventEnvelope[ConfigurablePersistentActor.Event]]): Unit = {
    log.info("Persisting {} events for tag {} for test {}", envelopes.size, tag, envelopes.headOption.map(_.event.testName).getOrElse("<unknown>"))
    session.withConnection { connection =>
      require(!connection.getAutoCommit)
      val values = envelopes.map(e => s"('${e.event.testName}', '${e.event.payload}')").mkString(",")
      connection.createStatement()
        .execute(s"insert into events(name, event) values $values")
    }
  }
}

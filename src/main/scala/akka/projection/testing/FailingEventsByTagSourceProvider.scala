package akka.projection.testing


import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.annotation.ApiMayChange
import akka.persistence.query.NoOffset
import akka.persistence.query.Offset
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.projection.eventsourced.EventEnvelope
import akka.projection.scaladsl.SourceProvider
import akka.stream.scaladsl.Source

import scala.util.Random
import scala.util.control.NoStackTrace

@ApiMayChange
object FailingEventsByTagSourceProvider {

  def eventsByTag[Event](
                          system: ActorSystem[_],
                          readJournalPluginId: String,
                          tag: String): SourceProvider[Offset, EventEnvelope[Event]] = {

    val eventsByTagQuery =
      PersistenceQuery(system).readJournalFor[EventsByTagQuery](readJournalPluginId)

    new FailingEventsByTagSourceProvider(eventsByTagQuery, tag, system)
  }

  private class FailingEventsByTagSourceProvider[Event](
                                                  eventsByTagQuery: EventsByTagQuery,
                                                  tag: String,
                                                  system: ActorSystem[_])
    extends SourceProvider[Offset, EventEnvelope[Event]] {
    implicit val executionContext: ExecutionContext = system.executionContext
    private val failEvery = system.settings.config.getInt("test.projection-failure-every")

    override def source(offset: () => Future[Option[Offset]]): Future[Source[EventEnvelope[Event], NotUsed]] =
      offset().map { offsetOpt =>
        val offset = offsetOpt.getOrElse(NoOffset)
        eventsByTagQuery
          .eventsByTag(tag, offset)
          .map { env =>
            if (Random.nextInt(failEvery) == 1) {
              throw new RuntimeException(s"Persistence id ${env.persistenceId} sequence nr ${env.sequenceNr} offset ${env.offset} Restart the stream!") with NoStackTrace
            }
            env
          }
          .map(env => EventEnvelope(env))

      }

    override def extractOffset(envelope: EventEnvelope[Event]): Offset = envelope.offset

    override def extractCreationTime(envelope: EventEnvelope[Event]): Long = envelope.timestamp
  }
}


package akka.projection.testing

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

object ConfigurablePersistentActor {

  val Key: EntityTypeKey[Command] = EntityTypeKey[Command]("configurable")

  def init(settings: EventProcessorSettings, system: ActorSystem[_]): ActorRef[ShardingEnvelope[Command]] = {
    ClusterSharding(system).init(Entity(Key)(ctx => apply(settings, ctx.entityId))
      .withRole("write-model"))
  }

  trait Command

  final case class PersistAndAck(totalEvents: Long, toPersist: String, replyTo: ActorRef[StatusReply[Done]], testName: String) extends Command
  final case class Event(testName: String, payload: String, timeCreated: Long = System.currentTimeMillis()) extends CborSerializable
  private final case class InternalPersist(totalEvents: Long, eventNr: Long, testName: String, toPersist: String, replyTo: ActorRef[StatusReply[Done]]) extends Command

  final case class State(eventsProcessed: Long) extends CborSerializable

  def apply(settings: EventProcessorSettings, persistenceId: String): Behavior[Command] =
    Behaviors.setup { ctx =>
      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.ofUniqueId(persistenceId),
        State(0),
        (state, command) => command match {
          case PersistAndAck(totalEvents, toPersist, ack, testName) =>
            ctx.log.info("persisting {} events", totalEvents)
            ctx.self ! InternalPersist(totalEvents, 1, testName, toPersist, ack)
            Effect.none
          case InternalPersist(totalEvents, eventNr, testName, toPersist, replyTo) =>
            if (state.eventsProcessed == totalEvents) {
              ctx.log.info("Finished persisting", totalEvents)
              replyTo ! StatusReply.ack()
              Effect.none
            } else {
              Effect.persist(Event(testName, payload = s"${toPersist}-${eventNr}")).thenRun {_ =>
                ctx.self ! InternalPersist(totalEvents, eventNr + 1, testName, toPersist, replyTo)
              }
            }
        },
        (state, _) => state.copy(eventsProcessed = state.eventsProcessed + 1)).withTagger(event =>
        Set("tag-" + math.abs(event.hashCode() % settings.parallelism)))
    }

}

package akka.projection.testing

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.cluster.sharding.typed.{ClusterShardingSettings, ShardedDaemonProcessSettings, ShardingEnvelope}
import akka.cluster.typed.Cluster
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.Offset
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.jdbc.scaladsl.JdbcProjection
import akka.projection.scaladsl.{GroupedProjection, SourceProvider}
import akka.projection.{ProjectionBehavior, ProjectionId}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

object Guardian {

  def createProjectionFor(index: Int,
                          factory: HikariFactory
                         )(implicit system: ActorSystem[_]) = {
    val tag = s"tag-$index"

    val sourceProvider: SourceProvider[Offset, EventEnvelope[ConfigurablePersistentActor.Event]] = FailingEventsByTagSourceProvider.eventsByTag[ConfigurablePersistentActor.Event](
      system = system,
      readJournalPluginId = CassandraReadJournal.Identifier,
      tag = tag)

    //    JdbcProjection.groupedWithin(
    //      projectionId = ProjectionId("test-projection-id", tag),
    //      sourceProvider,
    //      () => factory.newSession(),
    //      () => new GroupedProjectionHandler(tag, system)
    //    )

    JdbcProjection.exactlyOnce(
      projectionId = ProjectionId("test-projection-id", tag),
      sourceProvider,
      () => factory.newSession(),
      () => new ProjectionHandler(tag, system)
    )
  }

  def apply(): Behavior[String] = {
    Behaviors.setup[String] { context =>
      implicit val system: ActorSystem[_] = context.system
      // TODO config
      val config = new HikariConfig
      config.setJdbcUrl("jdbc:postgresql://127.0.0.1:5432/")
      config.setUsername("docker")
      config.setPassword("docker")
      config.setMaximumPoolSize(10)
      config.setAutoCommit(false)
      val dataSource = new HikariDataSource(config)
      val settings = EventProcessorSettings(system)
      val shardRegion = ConfigurablePersistentActor.init(settings, system)
      val loadGeneration: ActorRef[LoadGeneration.RunTest] = context.spawn(LoadGeneration(shardRegion, dataSource), "load-generation")

      val server = new HttpServer(new TestRoutes(loadGeneration).route, 8080)
      server.start()

      if (Cluster(system).selfMember.hasRole("read-model")) {

        val dbSessionFactory = new HikariFactory(dataSource)

        // we only want to run the daemon processes on the read-model nodes
        val shardingSettings = ClusterShardingSettings(system)
        val shardedDaemonProcessSettings =
          ShardedDaemonProcessSettings(system).withShardingSettings(shardingSettings.withRole("read-model"))

        ShardedDaemonProcess(system).init(
          name = "test-projection",
          settings.parallelism,
          n => ProjectionBehavior(createProjectionFor(n, dbSessionFactory)),
          shardedDaemonProcessSettings,
          Some(ProjectionBehavior.Stop))
      }


      Behaviors.empty
    }
  }
}

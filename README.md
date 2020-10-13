# Projections testing

This project tests event sourced actors events being tagged and read by a projection.

It is currently set up with Cassandra as the event sourcing event store and the projection using JDBC to 
provide exactly once delivery to the projection.

## Running a test

```
curl  -X POST  -v --data '{"name":"","nrActors":1000, "messagesPerActor": 100, "rate": 1000, "timeout": 600}' --header "Content
-Type: application/json"  localhost:8080/test
```

The params are:

* nrActors: How many persistent actors to create
* messagesPerActor: How many messages per actor, the total number of messages will be `nrActors * messagesPerActor`
* rate: How many actors to create per second
* timeout: How long to wait for all the messages to reach the projection in seconds

If the timeout is reached the response will be fail but you can check the logs to see if it eventually succeeded.

The test checks that every message makes it into the projection. These are stored in the `events` table. Duplciated
are detected with a primary key.

## Injecting failures

The projection will fail roughly 2% of the messages resulting in the projection being restarted from the last saved offset.
This helps tests the "exactly once" in the event of failures.

## Setup

* Cassandra on port 9042
* Postgres on port 5432 with user and password docker/docker. Not currently configurable see `Guardian.scala`

## Failure scenarios

### Projection restart

A known edge case is that a projection is restarted and delayed events from before the offset are then missed.
This should only happen in when multiple nodes are writing events as delayed event should still be written in offset 
order.




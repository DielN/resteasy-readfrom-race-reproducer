# RESTEasy context race with client calls in managed threads

If a server request hands work off to a `ManagedExecutorService` and two of those tasks make client calls whose
`readEntity(...)` overlaps, the server request can end up with the wrong `Providers`. The server request thread itself
makes no client call, only the tasks do. Still, afterwards its RESTEasy context holds the client's `ClientConfiguration` 
instead of the server's provider factory.

In our case the server-side `ContextResolver<Jsonb>` was then no longer found, and the response was silently serialized
with a default `Jsonb`.

## Reproduce

Tested with WildFly EE 41.0.1.Final and RESTEasy 7.0.3.Final.

```shell
./mvnw wildfly:dev
curl http://localhost:8080/race/api/race
```

The server endpoint prints the `Providers` in the server request's RESTEasy context before and after the tasks run.
You'd expect the server's provider factory both times:

```
org.jboss.resteasy.core.providerfactory.ResteasyProviderFactoryImpl -> org.jboss.resteasy.core.providerfactory.ResteasyProviderFactoryImpl
```

What you actually get is the client's configuration afterwards:

```
org.jboss.resteasy.core.providerfactory.ResteasyProviderFactoryImpl -> org.jboss.resteasy.client.jaxrs.internal.ClientConfiguration
```

All the code is in [`RaceResource`](src/main/java/org/example/race/RaceResource.java).

## Cause

Two things come together:

1. **WildFly (server side):** the RESTEasy context map of the server request is passed to managed threads by
   reference (`ResteasyContextHandleFactory`). The server request thread and all of its tasks share the same
   `HashMap`.
2. **RESTEasy client:** `ClientResponse.readFrom(...)`, running in a task, temporarily swaps `Providers` in that map
   for the client's `ClientConfiguration`. It saves the old value, puts in its own, and puts the old value back at
   the end.

With two tasks doing this at the same time, the second one saves the first client's value instead of the server's:

```
task A (client A)   saves ResteasyProviderFactoryImpl (server), sets ClientConfiguration A
task B (client B)   saves ClientConfiguration A, sets ClientConfiguration B
task A (client A)   restores ResteasyProviderFactoryImpl (server)
task B (client B)   restores ClientConfiguration A    <- and that's what the server request is left with
```

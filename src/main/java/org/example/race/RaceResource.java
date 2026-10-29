package org.example.race;

import java.util.concurrent.CompletableFuture;

import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ManagedExecutorService;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Providers;
import jakarta.ws.rs.ext.ReaderInterceptor;

import org.jboss.resteasy.core.ResteasyContext;

@Path("race")
@RequestScoped
public class RaceResource
{
    @Resource
    private ManagedExecutorService executor;

    @GET
    public String race()
    {
        String before = ResteasyContext.getContextData(Providers.class).getClass().getName();

        CompletableFuture<Void> firstReading = new CompletableFuture<>();
        CompletableFuture<Void> secondReading = new CompletableFuture<>();

        // The lambdas run inside readEntity(...) and wait for each other to force this order: first readEntity(...)
        // starts, second readEntity(...) starts, first finishes, second finishes. This only makes the race
        // deterministic; without it, it still happens, just not on every request.
        CompletableFuture<Void> first = this.executor.runAsync(() -> RaceResource.readEntity(() -> {
            firstReading.complete(null);
            secondReading.join();
        }));
        CompletableFuture<Void> second = this.executor.runAsync(() -> {
            firstReading.join();
            RaceResource.readEntity(() -> {
                secondReading.complete(null);
                first.join();
            });
        });
        second.join();

        String after = ResteasyContext.getContextData(Providers.class).getClass().getName();
        return before + " -> " + after;
    }

    private static void readEntity(Runnable insideReadEntity)
    {
        try (Client client = ClientBuilder.newClient()
                // no network needed, the aborted response is still read via ClientResponse.readFrom(...)
                .register((ClientRequestFilter) request -> request.abortWith(Response.ok("ok").build()))
                // the ReaderInterceptor runs in the middle of readEntity(...), so we use it to pause there
                .register((ReaderInterceptor) context -> {
                    insideReadEntity.run();
                    return context.proceed();
                }))
        {
            client.target("http://localhost").request().get().readEntity(String.class);
        }
    }
}

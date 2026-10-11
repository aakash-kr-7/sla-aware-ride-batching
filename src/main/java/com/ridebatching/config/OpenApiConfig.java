package com.ridebatching.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        final String securitySchemeName = "bearerAuth";
        return new OpenAPI()
                .info(new Info().title("SLA-Aware Ride Batching API")
                        .description("""
                                Send real requests to the employee ride-sharing backend. **Execute changes data for POST and DELETE operations.**

                                ### Start here
                                1. Expand **Authentication > Log in**, click **Try it out**, enter an existing account's credentials, then click **Execute**.
                                2. Copy the response's `token` value. Click **Authorize**, paste the token without quotes or a `Bearer` prefix, then **Authorize > Close**.
                                3. Open **Employee (Bookings) > List an employee's bookings**. Supply the existing employee's UUID as `X-Employee-Id`; it must match your account unless you are an admin.
                                4. To create a booking, supply pickup/dropoff coordinates. The pickup must be inside a configured active service zone. The server assigns the time slot, pickup deadline and status.
                                5. For pending bookings, log in as an admin, replace the authorized token and execute **Admin (Batching) > Plan trips and offer eligible drivers**.
                                6. Retrieve the booking by its returned `id`. `CONFIRMED` with a `tripId` means it joined a trip; driver acceptance is separate, and the passenger has not been picked up.
                                7. In **Admin (Dispatch simulation)**, list trips and copy a tripId into **Inspect one trip, grouped bookings and pickup promises**. Compare bookingIds, ordered stops, peakPassengers versus planningCapacity, and each pickup's saved estimate versus deadline. Leave candidateBookingId empty for ordinary inspection; on a PLANNED trip, optionally supply a booking outside the route to see a fresh hypothetical insertionCheck with rejection examples and any feasible proposed route. This check does not assign the booking or reconstruct past decisions. Copy the activeOfferId into **Simulate the offered driver's rejection** to see the next driver considered, then accept the new offer. These admin operations simulate the response of the driver stored on the offer; there is no driver identity input or driver login.

                                8. In **Admin (Execution simulation)**, execute pickup/dropoff for the next unfinished stop in the trip view, after driver acceptance. First pickup starts execution and closes cancellation for the whole trip; final dropoff completes the trip and releases its driver.

                                **Buttons:** endpoint rows expand instructions; **Try it out** enables editing; **Execute** sends the request; **Server response** shows the actual result. **Example Value** only describes the input shape.

                                ### How batching works
                                Each cycle claims a bounded set (default 100) from closed UTC collection windows, plus urgent requests within the configured deadline guard (default five minutes). New-trip groups stay within one source window, active pickup zone and compatible direction. Within each cluster, the planner processes pickup deadlines and chooses the cheapest feasible insertion from alternative pickup/dropoff positions, preserving existing stop order. It checks simultaneous seat occupancy, every rider's deadline and detour. If no shared insertion fits, it tries a separate trip. Four seats limit simultaneous riders; with enough time for turnover, more than four bookings can share a trip.

                                Unassigned planning starts optimistically at the first pickup. Offering and new acceptance include approach from the driver's stored position and recheck the route using current time. Acceptance persists refreshed arrival estimates; pending-offer estimates still reflect planning. A live offer can fail SLA before its expiry: 409 ROUTE_INFEASIBLE leaves it unassigned; Advance retires it and considers another feasible driver. Estimates use local distance, fixed speed/circuity and stop dwell, without real traffic or external routing.

                                ### Current boundaries
                                Startup does not create employee, zone or driver records. Replace examples with configured data. Repeated logical bookings return the existing booking; creating a booking may immediately insert it into an existing planned trip.

                                A batching response contains cycle counts, not full routes or total queue counts. Zero considered can mean saved requests are still collecting, expired or locked by another worker. Expired requests remain pending, excluded from planning claims, and get one open missed-promise issue through a separately bounded pass; infeasible or no-longer-serviceable claims remain pending too. Manual triggers respect the collection policy. Late insertion is a separate feasibility-based path. New trips attempt dispatch automatically. Offers last two minutes by default; rejection or expiry considers an untried driver, with at most three offers per trip. The expiry job runs every five seconds by default. No eligible driver, exhausted drivers and attempt limits are visible in trip outcomes; Advance retries when availability changes. Offers are database records, not delivered notifications. Escalation reading is read-only. Batching creates missed-solo-promise issues; cancellation recalculates arrival estimates and flags remaining pickup risk. Repeat cycles do not duplicate an unresolved issue. Cancellation resolves the cancelled booking's automatic issues. Pickup/dropoff are admin simulations, not measured road arrivals.
                                """)
                        .version("v0.0.1"))
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
                .components(
                        new Components()
                                .addSecuritySchemes(securitySchemeName,
                                        new SecurityScheme()
                                                .name(securitySchemeName)
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")
                                                .description("Paste only the token returned by POST /auth/login. Swagger adds the Bearer prefix. Log in again and replace this token when switching accounts.")
                                )
                );
    }
}

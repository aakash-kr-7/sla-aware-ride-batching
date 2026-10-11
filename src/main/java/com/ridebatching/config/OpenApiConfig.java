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
                                Employee ride pooling: create requests, inspect feasible routes, and simulate driver responses. **POST and DELETE operations change real application state.**

                                ### Start here
                                1. Start the explicit `local-fixtures` profile using the [local setup guide](https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/main/docs/local-development.md). Normal startup creates no business fixtures.
                                2. Expand **Authentication**, select **Try it out**, enter `local-admin` / `local-only-password` for that disposable setup, then **Execute**.
                                3. Copy the returned `token` into **Authorize** without quotes or a `Bearer` prefix. Login does not automatically authorize later calls.
                                4. Use **Admin (Dispatch simulation)** to list and inspect trips. The [review guide](https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/main/docs/reviewer-guide.md) creates five requests through real HTTP and pauses for inspection.
                                5. To book manually, log in as `local-rider-1` and supply `X-Employee-Id=00000000-0000-0000-0000-000000000001`. The pickup must be inside an active zone. Switch back to an admin token for batching and driver simulation.

                                **Buttons:** **Try it out** enables editing; **Execute** sends the request; **Server response** is the actual result. **Example Value** and **Schema** describe the contract.

                                ### Read the result
                                - `CONFIRMED` means a booking joined a feasible route. The trip stays `PLANNED` until a driver accepts; accepted assignment is `DISPATCHED`.
                                - `bookingIds` and ordered `stops` show who shares. Compare `peakPassengers` with `planningCapacity`, and each pickup estimate with its deadline.
                                - On single-trip GET, optional `candidateBookingId` checks hypothetical sharing with a PLANNED route. `insertionCheck` explains first-failure constraint examples and any feasible proposed route; it changes no assignment and is not historical decision storage.
                                - Copy `activeOfferId` into rejection, then use the returned new offer ID for acceptance. These ADMIN-only operations simulate the driver stored on the offer; there is no driver identity input.
                                - After acceptance, execute the next unfinished pickup/dropoff under **Admin (Execution simulation)**. First pickup closes cancellation for the whole trip; final dropoff releases the driver.

                                ### Planning and timing
                                Normal cycles claim at most 100 closed-window or urgent requests, group by source UTC window, active zone and compatible direction, then choose the cheapest feasible insertion while preserving existing stop order. Capacity, every pickup SLA and every rider's detour are hard constraints. Four seats bound simultaneous riders, not total bookings with turnover.

                                Planning starts optimistically at the first pickup. Offering and new acceptance recheck current-time driver approach and the full route. Acceptance saves refreshed estimates; a live but infeasible offer returns 409 ROUTE_INFEASIBLE. Estimates use local distance/speed/dwell, without traffic or actual arrival measurement.

                                ### Policy boundaries
                                Normal windows are 15 minutes, pickup promises 20 minutes, urgent lead time five minutes, offers two minutes and attempts three distinct drivers per trip. The explicit local fixture profile uses a 20-minute urgent guard and ten-minute offers for a short walkthrough; route constraints remain enabled.

                                Manual batching respects collection policy. Zero considered can mean collecting, expired or locked requests. Expired solo promises stay pending with one open SLA issue. Creating a booking may instead perform late insertion into a planned trip. Cancellation replans only its trip before execution and flags remaining pickup risk. Offers are provisional records, not delivered notifications; Advance considers untried eligible drivers without resetting history. See endpoint descriptions and the repository guides for failure outcomes and concurrency evidence.
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

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
                                Create employee ride requests, plan shared routes and follow driver assignment. The [browser demo](/) provides a guided example and editable rider scenarios.

                                ### Authenticate
                                With the `local-fixtures` profile running, use **Authentication > Try it out** to log in as `local-admin` with `local-only-password`. Paste the returned token into **Authorize**, without quotes or a `Bearer` prefix. See the [setup guide](https://github.com/aakash-kr-7/sla-aware-ride-batching/blob/main/docs/local-development.md) for startup and accounts.

                                For employee booking calls, log in as `local-rider-1` and set `X-Employee-Id` to `00000000-0000-0000-0000-000000000001`. The header must match the employee account. Admins can operate on behalf of an employee; cancellation still requires the owner's header. Switch back to the admin token for batching, dispatch and execution.

                                ### Follow a trip
                                1. Create bookings, then trigger batching. Inspect `bookingIds`, ordered `stops`, `peakPassengers` and pickup deadlines on the trip response.
                                2. Copy `activeOfferId` into accept or reject. Rejection attempts the next eligible driver; acceptance commits assignment after checking current driver approach and route constraints.
                                3. Record pickups and dropoffs in route order. The first pickup closes cancellation for the trip; the final dropoff releases its driver.

                                `CONFIRMED` is a booking's route association. The trip remains `PLANNED` until acceptance makes it `DISPATCHED`. Offers do not reserve drivers. On a planned trip, optional `candidateBookingId` tests a hypothetical insertion without changing the route.

                                ### Timing
                                Normal collection windows are 15 minutes, pickup deadlines 20 minutes, the urgency guard five minutes and offer expiry two minutes. Local fixtures use a 20-minute guard and ten-minute offers for the demo. Capacity, detour and acceptance checks remain enabled. Estimates use local distance, fixed speed and stop dwell; they are not measured arrivals.

                                POST and DELETE calls change saved state. A batching result with zero considered requests can mean collecting, already confirmed, expired or locked requests. Inspect booking status and open SLA issues before retrying. Endpoint descriptions below cover inputs and failure outcomes.
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

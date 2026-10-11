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
                                5. For pending bookings, log in as an admin, replace the authorized token and execute **Admin (Batching) > Plan trips for pending bookings**.
                                6. Retrieve the booking by its returned `id`. `CONFIRMED` with a `tripId` means it joined a trip; it does not mean the passenger was picked up.

                                **Buttons:** endpoint rows expand instructions; **Try it out** enables editing; **Execute** sends the request; **Server response** shows the actual result. **Example Value** only describes the input shape.

                                ### How batching works
                                Pending requests are processed by pickup deadline. The planner tries adding each pickup/dropoff to a route and checks stop order, simultaneous seat occupancy, every rider's pickup deadline and detour. A feasible shared route is accepted; otherwise it tries a separate trip. A request infeasible even alone stays pending.

                                ### Current boundaries
                                Startup does not create employee, zone or driver records. Replace examples with configured data. Repeated logical bookings return the existing booking; creating a booking may immediately insert it into an existing planned trip.

                                A batching response contains counts, not full routes. Zero bookings considered means no pending rows were claimed. Runtime window/direction clustering is not connected. Driver offers are Java services without HTTP operations; batching does not automatically dispatch. Escalation reading does not create escalations.
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

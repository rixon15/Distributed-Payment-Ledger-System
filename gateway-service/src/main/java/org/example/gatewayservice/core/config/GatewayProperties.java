package org.example.gatewayservice.core.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param publicBaseUrl externally visible {@code scheme://host[:port]} of the gateway. Used to rebuild the exact
 *                      DPoP {@code htu} the client signed, independent of any client-supplied forwarding headers.
 */
@Validated
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(@NotBlank String publicBaseUrl) {

    public GatewayProperties {
        if (publicBaseUrl != null && publicBaseUrl.endsWith("/")) {
            publicBaseUrl = publicBaseUrl.substring(0, publicBaseUrl.length() - 1);
        }
    }

}

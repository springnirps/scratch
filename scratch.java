package com.example.cas.delegated;

import org.apereo.cas.support.pac4j.authentication.DelegatedClientAuthenticationRequestCustomizer;
import org.pac4j.core.client.Client;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

@Component
public class StoreDelegatedRequestCustomizer implements DelegatedClientAuthenticationRequestCustomizer {

    @Override
    public Map<String, String> customizeRequestParameters(
        final Map<String, String> parameters,
        final HttpServletRequest request,
        final Client client
    ) {
        // Copy parameters so we don't mutate the input map directly (CAS may use immutable maps)
        Map<String, String> updated = new HashMap<>(parameters);

        // Read TARGET from the original CAS request
        String target = request.getParameter("TARGET");
        if (target != null && target.contains("foobar/store")) {
            // Append cas_client=store to the outgoing authn request
            updated.put("cas_client", "store");
        }

        return updated;
    }
}

This bean is auto-discovered if component scanning covers its package; otherwise, declare it explicitly in your CAS configuration class:

@Bean
public DelegatedClientAuthenticationRequestCustomizer storeDelegatedRequestCustomizer() {
    return new StoreDelegatedRequestCustomizer();
}

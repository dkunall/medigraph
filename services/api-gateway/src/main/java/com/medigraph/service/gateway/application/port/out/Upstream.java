package com.medigraph.service.gateway.application.port.out;

import com.medigraph.service.gateway.application.ProxyRequest;
import com.medigraph.service.gateway.application.ProxyResponse;
import com.medigraph.service.gateway.domain.RouteTarget;

/** Sends a request to a backend service. Throws {@code UpstreamUnavailableException} when it cannot be reached. */
public interface Upstream {
    ProxyResponse forward(RouteTarget target, ProxyRequest request);
}

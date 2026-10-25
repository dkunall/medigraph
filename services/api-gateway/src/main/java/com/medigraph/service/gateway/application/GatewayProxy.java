package com.medigraph.service.gateway.application;

import com.medigraph.service.gateway.application.port.out.Upstream;

/** Use case: route a request to its backend and relay the answer unchanged. */
public final class GatewayProxy {

    private final RouteTable routes;
    private final Upstream upstream;

    public GatewayProxy(RouteTable routes, Upstream upstream) {
        this.routes = routes;
        this.upstream = upstream;
    }

    public ProxyResponse handle(ProxyRequest request) {
        return upstream.forward(routes.resolve(request.path()), request);
    }
}

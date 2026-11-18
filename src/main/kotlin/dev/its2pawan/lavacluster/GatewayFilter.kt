package dev.its2pawan.lavacluster

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** Sends every /v4/** call of the main port through the cluster gateway (except /v4/cluster). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class GatewayFilter(private val gw: ClusterGateway) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val p = request.requestURI
        return !gw.active || !p.startsWith("/v4/") || p.startsWith("/v4/cluster")
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        if (request.requestURI == "/v4/websocket") {
            // hand the socket to our own handler instead of this node's normal one
            filterChain.doFilter(RewrittenRequest(request, "/lavacluster/gateway"), response)
            return
        }
        gw.handleRest(request, response)
    }
}

class RewrittenRequest(req: HttpServletRequest, private val newPath: String) : HttpServletRequestWrapper(req) {
    override fun getRequestURI(): String = newPath
    override fun getServletPath(): String = newPath
    override fun getPathInfo(): String? = null
    override fun getRequestURL(): StringBuffer = StringBuffer("$scheme://$serverName:$serverPort$newPath")
}

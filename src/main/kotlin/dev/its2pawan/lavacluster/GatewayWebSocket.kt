package dev.its2pawan.lavacluster

import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.TextWebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor

class GatewayWebSocketHandler(private val gw: ClusterGateway) : TextWebSocketHandler() {
    override fun afterConnectionEstablished(session: WebSocketSession) = gw.attach(session)
    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) = gw.fromClient(session.id, message.payload)
    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) = gw.detach(session.id)
    override fun handleTransportError(session: WebSocketSession, exception: Throwable) = gw.detach(session.id)
}

class GatewayAuth(private val gw: ClusterGateway) : HandshakeInterceptor {
    override fun beforeHandshake(
        request: ServerHttpRequest, response: ServerHttpResponse,
        wsHandler: WebSocketHandler, attributes: MutableMap<String, Any>,
    ): Boolean {
        if (!gw.authorized(request.headers.getFirst("Authorization"))) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED); return false
        }
        if (request.headers.getFirst("User-Id").isNullOrBlank()) {
            response.setStatusCode(HttpStatus.BAD_REQUEST); return false
        }
        return true
    }

    override fun afterHandshake(request: ServerHttpRequest, response: ServerHttpResponse, wsHandler: WebSocketHandler, exception: Exception?) {}
}

@Configuration
@EnableWebSocket
class GatewayWsConfig(private val gw: ClusterGateway) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        if (!gw.active) return
        registry.addHandler(GatewayWebSocketHandler(gw), "/lavacluster/gateway")
            .addInterceptors(GatewayAuth(gw))
            .setAllowedOrigins("*")
    }
}

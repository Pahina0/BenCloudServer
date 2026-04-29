package gov.epa.bencloud.server.websocket;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import spark.Spark;

public class WebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketServer.class);

    public static void initWebSocket(int port) {
        try {
            Server server = new Server(port);
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            server.setHandler(context);

            ServletHolder wsHolder = new ServletHolder("ws", TaskNotificationServlet.class);
            context.addServlet(wsHolder, "/ws/task-notifications/*");

            server.start();
            log.info("WebSocket server started on port " + port);
        } catch (Exception e) {
            log.error("Failed to start WebSocket server", e);
        }
    }

    public static class TaskNotificationServlet extends WebSocketServlet {
        @Override
        public void configure(WebSocketServletFactory factory) {
            factory.register(TaskNotificationWebSocket.class);
        }
    }
}

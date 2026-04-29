package gov.epa.bencloud.server.websocket;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@WebSocket
public class TaskNotificationWebSocket {

    private static final Logger log = LoggerFactory.getLogger(TaskNotificationWebSocket.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Map of batchTaskId to set of sessions listening for that batch task
    private static final Map<String, Set<Session>> batchTaskListeners = new ConcurrentHashMap<>();

    @OnWebSocketConnect
    public void onConnect(Session session) {
        log.info("WebSocket connected: " + session.getRemoteAddress());
    }

    @OnWebSocketClose
    public void onClose(Session session, int statusCode, String reason) {
        log.info("WebSocket closed: " + session.getRemoteAddress() + " Reason: " + reason);
        removeSession(session);
    }

    @OnWebSocketMessage
    public void onMessage(Session session, String message) {
        log.info("WebSocket message received: " + message);

        try {
            ObjectNode jsonMessage = objectMapper.readValue(message, ObjectNode.class);
            String action = jsonMessage.get("action").asText();

            if ("subscribe".equals(action)) {
                String batchTaskId = jsonMessage.get("batchTaskId").asText();
                subscribeToBatchTask(session, batchTaskId);

                // Send acknowledgment
                ObjectNode response = objectMapper.createObjectNode();
                response.put("type", "subscribed");
                response.put("batchTaskId", batchTaskId);
                session.getRemote().sendString(response.toString());
            } else if ("unsubscribe".equals(action)) {
                String batchTaskId = jsonMessage.get("batchTaskId").asText();
                unsubscribeFromBatchTask(session, batchTaskId);
            }
        } catch (Exception e) {
            log.error("Error processing WebSocket message", e);
        }
    }

    private void subscribeToBatchTask(Session session, String batchTaskId) {
        batchTaskListeners.computeIfAbsent(batchTaskId, k -> new CopyOnWriteArraySet<>()).add(session);
        log.info("Session " + session.getRemoteAddress() + " subscribed to batch task " + batchTaskId);
    }

    private void unsubscribeFromBatchTask(Session session, String batchTaskId) {
        Set<Session> sessions = batchTaskListeners.get(batchTaskId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                batchTaskListeners.remove(batchTaskId);
            }
        }
        log.info("Session " + session.getRemoteAddress() + " unsubscribed from batch task " + batchTaskId);
    }

    private void removeSession(Session session) {
        for (Set<Session> sessions : batchTaskListeners.values()) {
            sessions.remove(session);
        }
        batchTaskListeners.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    /**
     * Notify all listeners that a batch task has completed
     * @param batchTaskId The batch task ID that completed
     * @param successful Whether the task completed successfully
     * @param message Completion message
     */
    public static void notifyBatchTaskComplete(String batchTaskId, boolean successful, String message) {
        Set<Session> sessions = batchTaskListeners.get(batchTaskId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        try {
            ObjectNode notification = objectMapper.createObjectNode();
            notification.put("type", "batchTaskComplete");
            notification.put("batchTaskId", batchTaskId);
            notification.put("successful", successful);
            notification.put("message", message);

            String notificationJson = notification.toString();

            for (Session session : sessions) {
                if (session.isOpen()) {
                    try {
                        session.getRemote().sendString(notificationJson);
                    } catch (IOException e) {
                        log.error("Error sending WebSocket message to session", e);
                    }
                }
            }

            log.info("Sent batch task completion notification for batch task " + batchTaskId + " to " + sessions.size() + " listeners");
        } catch (Exception e) {
            log.error("Error creating WebSocket notification", e);
        }
    }

    /**
     * Notify all listeners of task progress
     * @param batchTaskId The batch task ID
     * @param taskUuid The individual task UUID
     * @param percentage Completion percentage
     * @param message Progress message
     */
    public static void notifyTaskProgress(String batchTaskId, String taskUuid, int percentage, String message) {
        Set<Session> sessions = batchTaskListeners.get(batchTaskId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        try {
            ObjectNode notification = objectMapper.createObjectNode();
            notification.put("type", "taskProgress");
            notification.put("batchTaskId", batchTaskId);
            notification.put("taskUuid", taskUuid);
            notification.put("percentage", percentage);
            notification.put("message", message);

            String notificationJson = notification.toString();

            for (Session session : sessions) {
                if (session.isOpen()) {
                    try {
                        session.getRemote().sendString(notificationJson);
                    } catch (IOException e) {
                        log.error("Error sending WebSocket message to session", e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error creating WebSocket progress notification", e);
        }
    }
}

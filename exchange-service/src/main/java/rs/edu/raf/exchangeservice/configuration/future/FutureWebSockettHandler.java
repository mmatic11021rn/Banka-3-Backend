package rs.edu.raf.exchangeservice.configuration.future;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import rs.edu.raf.exchangeservice.domain.model.myListing.MyFuture;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class FutureWebSockettHandler extends TextWebSocketHandler {

    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        this.sessions.add(session);
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) {

    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        this.sessions.remove(session);
    }

    @EventListener
    public void sendFutureUpdate(FutureUpdateEvent event){
        MyFuture futureUpdate = event.getFuture();
        try {
            for (WebSocketSession session : this.sessions) {
                session.sendMessage(new TextMessage(futureUpdate.toString()));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

package hao.webrtc_server.socket;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.util.ArrayUtils;

import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

@Component
@Slf4j
@ServerEndpoint("/websocket/{userId}")  // 接口路径 ws://localhost:8087/webSocket/userId;
public class WebSocket {
    
    //与某个客户端的连接会话，需要通过它来给客户端发送数据
    private Session session;
        /**
     * 用户ID
     */
    private String userId;
    
    //concurrent包的线程安全Set，用来存放每个客户端对应的MyWebSocket对象。
    //虽然@Component默认是单例模式的，但springboot还是会为每个websocket连接初始化一个bean，所以可以用一个静态set保存起来。
    //  注：底下WebSocket是当前类名
    private static CopyOnWriteArraySet<WebSocket> webSockets =new CopyOnWriteArraySet<>();
    // 用来存在线连接用户信息
    private static ConcurrentHashMap<String,Session> sessionPool = new ConcurrentHashMap<>();
    // 用来存在线连接用户信息
    private static ConcurrentHashMap<String,CopyOnWriteArraySet<String>> rooms = new ConcurrentHashMap<>();
    
    /**
     * 链接成功调用的方法
     */
    @OnOpen
    public void onOpen(Session session, @PathParam(value="userId")String userId) {
        try {
			this.session = session;
			this.userId = userId;
			webSockets.add(this);
			sessionPool.put(userId, session);
			log.info("【websocket消息】有新的连接，总数为:"+webSockets.size());
		} catch (Exception e) {
		}
    }
    
    /**
     * 链接关闭调用的方法
     */
    @OnClose
    public void onClose() {
        try {
			webSockets.remove(this);
			sessionPool.remove(this.userId);
			log.info("【websocket消息】连接断开，总数为:"+webSockets.size());
			// 查找用户所在的房间
            rooms.forEach((roomId, list) -> {
                list.remove(this.userId);
            });
		} catch (Exception e) {
		}
    }
    /**
     * 收到客户端消息后调用的方法
     *
     * @param message
     */
    @OnMessage
    public void onMessage(String message) {
    	log.info("【websocket消息】收到客户端消息:"+message);
    	// 加入房间
        try {
            JSONObject jsonObject = JSON.parseObject(message);
            String handle = jsonObject.getString("handle");
            messageHandler(handle, jsonObject);
        }catch (Exception e){
            log.error(e.getMessage());
        }
    }


    
	  /** 发送错误时的处理
     * @param session
     * @param error
     */
    @OnError
    public void onError(Session session, Throwable error) {

        log.error("用户错误,原因:"+error.getMessage());
        error.printStackTrace();
    }

    
    // 此为广播消息
    public void sendAllMessage(String message) {
    	log.info("【websocket消息】广播消息:"+message);
        for(WebSocket webSocket : webSockets) {
            try {
            	if(webSocket.session.isOpen()) {
            		webSocket.session.getAsyncRemote().sendText(message);
            	}
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    // 向房间内的所有成员广播消息
    public void sendAllMessageToRoom(String roomId, JSONObject jsonObject) {
        log.info("【websocket消息】广播消息:"+jsonObject.toString());
        CopyOnWriteArraySet<String> strings = rooms.get(roomId);
        for(String String : strings){
            Session session = sessionPool.get(String);
            if(session.isOpen()){
                session.getAsyncRemote().sendText(jsonObject.toString());
            }

        }
    }
    
    // 此为单点消息
    public void sendOneMessage(String userId, String message) {
        Session session = sessionPool.get(userId);
        if (session != null&&session.isOpen()) {
            try {
            	log.info("【websocket消息】 单点消息:"+message);
                session.getAsyncRemote().sendText(message);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
    
    // 此为单点消息(多人)
    public void sendMoreMessage(Collection<String> userIds, String message) {
    	for(String userId:userIds) {
    		Session session = sessionPool.get(userId);
            if (session != null&&session.isOpen()) {
                try {
                	log.info("【websocket消息】 单点消息:"+message);
                    session.getAsyncRemote().sendText(message);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
    	}
        
    }



    private void messageHandler(String message, JSONObject jsonObject){
        if(!StringUtils.hasLength(message)){
            log.error("message is null");
            return;
        }
        switch (message){
            case "join":
                joinHandler(jsonObject);
                break;
            case "leave":
                leaveHandler(jsonObject);
                break;
            case "p2p":
                p2pHandler(jsonObject);
                break;
            case "roomChat":
                roomChatHandler(jsonObject);
                break;
            default:
                log.error("undefine message");
        }
    }


    private void p2pHandler(JSONObject jsonObject){
        JSONObject backMessage = new JSONObject();
        JSONObject data = jsonObject.getJSONObject("data");
        String roomId = data.getString("roomId");
        // 遍历roomId中的用户 遍历发送
        CopyOnWriteArraySet<String> strings = rooms.get(roomId);
        if(strings.isEmpty()){
            return;
        }
        strings.forEach(s -> {
            if(s.equals(this.userId)){
                return;
            }
            backMessage.put("message", "收到sdp信息");
            backMessage.put("type","p2p");
            backMessage.put("data",data.get("desc"));
            backMessage.put("userId",s);
            sendOneMessage(s,backMessage.toString());
        });


    }

    private void joinHandler(JSONObject jsonObject){
        JSONObject backMessage = new JSONObject();
        JSONObject data = jsonObject.getJSONObject("data");
        String roomId = data.getString("roomId");
        backMessage.put("roomId", roomId);
        backMessage.put("userId", this.userId);
        CopyOnWriteArraySet<String> strings = rooms.get(roomId);
        String message = "";
        if(strings == null || strings.isEmpty()){
            strings = new  CopyOnWriteArraySet<>();
        }else if(strings.size()==2){
            backMessage.put("type", "full");
            sendOneMessage(this.userId,backMessage.toString());
            return;
        }
        strings.add(this.userId);
        rooms.put(roomId, strings);
        for(String userId : strings){
            backMessage.put("roomId", roomId);
            backMessage.put("userId", userId);
            if(userId.equals(this.userId)){
                backMessage.put("type", "joined");
            }else{
                backMessage.put("type", "otherjoin");
            }
            backMessage.put("message", userId+"加入聊天");
            sendOneMessage(userId,backMessage.toString());
        }
    }


    private void leaveHandler(JSONObject jsonObject){
        JSONObject data = jsonObject.getJSONObject("data");
        String roomId = data.getString("roomId");
        CopyOnWriteArraySet<String> strings = rooms.get(roomId);
        if(strings == null || strings.isEmpty() || !strings.contains(this.userId)){
            return;
        }
        // 发送消息到房间
        for(String userId : strings){
            JSONObject backMessage = new JSONObject();
            backMessage.put("roomId", roomId);
            backMessage.put("userId", userId);
            if(userId.equals(this.userId)){
                backMessage.put("type", "leaved");
            }else{
                backMessage.put("type", "bye");
            }
            backMessage.put("message", this.userId+"离开了聊天");
            sendOneMessage(userId,backMessage.toString());
        }
        strings.remove(this.userId);
        if(strings.size() == 0){
            rooms.remove(roomId);
        }else{
            rooms.put(roomId, strings);
        }
    }



    private void roomChatHandler(JSONObject jsonObject){
        JSONObject data = jsonObject.getJSONObject("data");
        String roomId = data.getString("roomId");
        sendAllMessageToRoom(roomId, data.getJSONObject("chatMessage"));
    }


    
}

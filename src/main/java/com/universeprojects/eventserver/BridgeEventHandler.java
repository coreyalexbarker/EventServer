package com.universeprojects.eventserver;

import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.handler.sockjs.BridgeEvent;

public class BridgeEventHandler implements Handler<BridgeEvent> {

    private Logger log = LoggerFactory.getLogger(getClass());

    private final EventServerVerticle eventServerVerticle;
    private final SharedDataService sharedDataService;

    public BridgeEventHandler(EventServerVerticle eventServerVerticle) {
        this.eventServerVerticle = eventServerVerticle;
        this.sharedDataService = eventServerVerticle.sharedDataService;
    }

    @Override
    public void handle(BridgeEvent be) {
        JsonObject msg = be.getRawMessage();
        if (msg != null) {
            log.info(be.getRawMessage().encode());
        }
        switch (be.type()) {
            // Client attempts to Send
            case SEND: {
                log.info("Handling EventBus Socket Message Send");
                String userId = getSocketUserId(be);
                if (userId != null && msg != null) {
                    // Associate the send message with the Socket's user
                    JsonObject headers = new JsonObject().put("userId", userId);
                    msg.put("headers", headers);
                    be.setRawMessage(msg);
                    be.complete(true);
                } else {
                    // Deny the send if no user is associated with the Socket
                    be.complete(false);
                }
            }
            break;
            // Client attempts to Publish
            case PUBLISH:
                log.info("Handling EventBus Socket Message Publish");
                // Prevent websocket clients from publishing
                be.complete(false);
                break;
            // Message goes out from Server to Client
            case RECEIVE: {
                log.info("Handling EventBus Socket Message Recieve");
                String userId = getSocketUserId(be);
                if (userId != null) {
                    log.info("userid: " + userId);
                    be.complete(eventServerVerticle.filterChat(be, userId));
                } else {
                    log.info("Recieving User is null");
                    be.complete(false);
                }
            }
            break;
            // Client attempts to register
            case REGISTER: {
                log.info("Handling EventBus Socket Message Register");
                if(msg == null) {
                    log.info("Bad register request: "+be.getRawMessage().encode());
                    be.complete(false);
                    return;
                }
                String userId = getSocketUserId(be);
                JsonObject savedMessagesBody = new JsonObject();
                savedMessagesBody.put("address", msg.getString("address"));
                savedMessagesBody.put("userId", userId);
                savedMessagesBody.put("socketId", be.socket().writeHandlerID());
                if (msg.getJsonObject("headers").containsKey("Last-Recieved")) {
                    savedMessagesBody.put("date", msg.getJsonObject("headers").getLong("Last-Recieved"));
                } else {
                    savedMessagesBody.put("date", 0);
                }
                if (userId != null) {
                    log.info("Socket is already authenticated!");
                    be.complete(true);
                    eventServerVerticle.sendSavedMessages(savedMessagesBody);
                } else {
                    // Uses the Headers sent with the register request for authentication
                    // Specifically it looks for Auth-Token
                    eventServerVerticle.authenticate(be.getRawMessage().getJsonObject("headers"), res -> {
                        if (res.succeeded()) {
                            JsonObject user = res.result();
                            log.info("AuthUser: " + user.encode());
                            log.info("AuthUser: " + user.encode());
                            // Caches the userId for this socket so it doesn't hit the server for subsequent registers
                            sharedDataService.getSocketMap().put(be.socket().writeHandlerID(), user.getString("accountId"));
                            log.info(be.socket().writeHandlerID());
                            be.complete(true);
                            eventServerVerticle.sendSavedMessages(savedMessagesBody);
                        } else {
                            log.info("Unable to authenticate", res.cause());
                            be.complete(false);
                        }
                    });
                }
            }
            break;
            // Client attempts to unregister
            case UNREGISTER:
                log.info("Handling EventBus Socket Message Unregister");
                be.complete(true);
                break;
            case SOCKET_CLOSED: {
                log.info("Handling EventBus Socket Message Socket_Closed");
                // Cleanup the userId associated with the socket
                String userId = getSocketUserId(be);
                if (userId != null) {
                    sharedDataService.getSocketMap().remove(userId);
                    log.info("Socket authentication was removed!");
                }
                be.complete(true);
            }
            break;
            case SOCKET_CREATED:
                log.info("Handling EventBus Socket Message Socket_Created");
                be.complete(true);
                break;
            default:
                log.info("Handling EventBus Socket Message Unknown_Type");
                break;
        }
    }


    public String getSocketUserId(BridgeEvent be) {
        return sharedDataService.getSocketMap().get(be.socket().writeHandlerID());
    }
}

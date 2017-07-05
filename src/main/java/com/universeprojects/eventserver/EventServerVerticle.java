package com.universeprojects.eventserver;


import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Cookie;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.sockjs.BridgeEvent;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.ext.web.handler.sockjs.PermittedOptions;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;

public class EventServerVerticle extends AbstractVerticle {
    private final Logger log = LoggerFactory.getLogger(getClass());
    private HttpClient client;
    private EventBus eventBus;
    @SuppressWarnings("FieldCanBeLocal")
    private BridgeEventHandler bridgeEventHandler;
    public SharedDataService sharedDataService;

    @Override
    public void start() {
        HttpClientOptions options = new HttpClientOptions().
                setDefaultHost("test-dot-playinitium.appspot.com").setDefaultPort(443).setSsl(true);
        client = vertx.createHttpClient(options);
        HttpServer server = vertx.createHttpServer();
        Router router = Router.router(vertx);

        Route indexRoute = router.route("/");
        indexRoute.handler(routingContext -> {
            routingContext.addCookie(Cookie.cookie("test", "Hello!"));
            routingContext.response().sendFile("index.html");
        });

        eventBus = vertx.eventBus();
        sharedDataService = new SharedDataService(vertx.sharedData());
        String[] chats = {
                "public",
                "group",
                "party",
                "location",
                "private",
                "notifications"
        };
        BridgeOptions bridgeOpts = new BridgeOptions();
        for (String chat : chats) {
            // handle clients sending in messages
            eventBus.consumer("chat." + chat + ".in", message -> consumeIncomingChat(chat, message));
            PermittedOptions inboundPermitted = new PermittedOptions().setAddress("chat." + chat + ".in");
            PermittedOptions outboundPermitted = new PermittedOptions().setAddress("chat." + chat + ".out");
            bridgeOpts.addInboundPermitted(inboundPermitted);
            bridgeOpts.addOutboundPermitted(outboundPermitted);
        }

        SockJSHandler ebusSockJSHandler = SockJSHandler.create(vertx);
        bridgeEventHandler = new BridgeEventHandler(this);
        ebusSockJSHandler.bridge(bridgeOpts, bridgeEventHandler);

        //AuthHandler basicAuthHandler = BasicAuthHandler.create(authProvider);
        //router.route("/eventbus/*").handler(basicAuthHandler);
        router.route("/eventbus/*").handler(ebusSockJSHandler);

        server.requestHandler(router::accept).listen(6969, "0.0.0.0");
    }

    public void sendSavedMessagesBlocking(JsonObject body) {
        vertx.executeBlocking((future) -> {
            sendSavedMessages(body);
            future.complete();
        }, (result) -> {});
    }

    public void sendSavedMessages(JsonObject body) {
        log.info(body.encode());
        String socketId = body.getString("socketId");
        String address = body.getString("address");
        String userId = body.getString("userId");
        Long time = body.getLong("date");
        JsonArray msgsJson = new JsonArray();
        JsonArray msgs = null;
        switch (address) {
            case "chat.private.out":
                msgs = sharedDataService.getMessageMap().get(userId + "#private");
                break;
            case "chat.public.out":
                msgs = sharedDataService.getMessageMap().get("publicChat");
                break;
            case "chat.group.out":
                msgs = sharedDataService.getMessageMap().get(sharedDataService.getGroupMap().get(userId));
                break;
            case "chat.location.out":
                msgs = sharedDataService.getMessageMap().get(sharedDataService.getLocationMap().get(userId));
                break;
            case "chat.party.out":
                msgs = sharedDataService.getMessageMap().get(sharedDataService.getPartyMap().get(userId));
                break;
        }
        if (msgs != null) {
            msgs.forEach(msg -> {
                if (((JsonObject) msg).getLong("createdDate") > time) {
                    msgsJson.add(msg);
                }
            });
        }
        publishSocketMessage(address, socketId, msgsJson);
    }

    private void consumeIncomingChat(String chat, Message<Object> message) {
        log.info("I have received a message: " + message.body());
        String uid = message.headers().get("userId");
        JsonObject body = (JsonObject) message.body();
        body.put("channel", chat);
        body.put("accountId", uid);
        // body is a json object that represents
        // what the clients send through the websocket
        // in addition to a channel field and the accountId
        formatChatMsg(body, resp -> {
            if (resp.succeeded()) {
                // get JSON payload returned from app engine service
                JsonObject jsonResp = resp.result();
                DeliveryOptions opts = new DeliveryOptions();
                if (jsonResp.containsKey("id")) {
                    opts.addHeader("id", jsonResp.getString("id"));
                }
                if (jsonResp.containsKey("payload")) {
                    eventBus.publish("chat." + chat + ".out", jsonResp.getJsonObject("payload"), opts);
                    saveMessage(chat, jsonResp);
                }
            }
        });
    }

    public void authenticate(JsonObject authInfo, Handler<AsyncResult<JsonObject>> resultHandler) {
        if (authInfo.containsKey("Auth-Token")) {
            HttpClientRequest request = client.post("/eventserver?type=auth", response -> {
                response.bodyHandler(respBody -> {
                    try {
                        JsonObject body = respBody.toJsonObject();
                        if (body.getBoolean("success")) {
                            String id = body.getString("accountId");
                            sharedDataService.getLocationMap().put(id, body.getString("locationId"));
                            sharedDataService.getGroupMap().put(id, body.getString("groupId"));
                            sharedDataService.getPartyMap().put(id, body.getString("partyId"));
                            resultHandler.handle(Future.succeededFuture(body));
                        } else {
                            resultHandler.handle(Future.failedFuture("Auth-Token was rejected by the server"));
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        resultHandler.handle(Future.failedFuture("Invalid response from server: " + respBody.toString()));
                    }
                });
            });
            // TODO: handle http errors
            JsonObject reqBody = new JsonObject();
            reqBody.put("Auth-Token", authInfo.getString("Auth-Token"));
            request.exceptionHandler(err -> {
                log.info("Recieved exception: " + err.getMessage());
                resultHandler.handle(Future.failedFuture("Authentican Request failed"));
            });
            request.putHeader("content-type", "application/json");
            String raw = reqBody.encode();
            request.putHeader("content-length", Integer.toString(raw.length()));
            request.write(raw);
            request.end();
        } else {
            resultHandler.handle(Future.failedFuture("Auth-Token was not provided"));
        }

    }

    private void formatChatMsg(JsonObject reqBody, Handler<AsyncResult<JsonObject>> resultHandler) {
        HttpClientRequest request = client.post("/eventserver?type=message", response -> {
            response.bodyHandler(respBody -> {
                try {
                    JsonObject body = respBody.toJsonObject();
                    if (body.getBoolean("success")) {
                        resultHandler.handle(Future.succeededFuture(body));
                    } else {
                        resultHandler.handle(Future.failedFuture("Chat Message was rejected by server"));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        });
        // TODO: handle http errors
        request.exceptionHandler(err -> {
            log.info("Recieved exception: " + err.getMessage());
            resultHandler.handle(Future.failedFuture("Message Format Request failed"));
        });
        request.putHeader("content-type", "application/json");
        String raw = reqBody.encode();
        request.putHeader("content-length", Integer.toString(raw.length()));
        request.write(raw);
        request.end();
    }

    // returns true if the message should be sent, returns false if the message should not be sent
    public boolean filterChat(BridgeEvent be, String userId) {
        JsonObject msg = be.getRawMessage();
        JsonObject headers = msg.getJsonObject("headers");
        // clear headers
        msg.put("headers", new JsonObject());
        be.setRawMessage(msg);
        switch (msg.getString("address")) {
            case "chat.location.out":
                return headers.getString("id").equals(sharedDataService.getLocationMap().get(userId));
            case "chat.group.out":
                return headers.getString("id").equals(sharedDataService.getGroupMap().get(userId));
            case "chat.party.out":
                return headers.getString("id").equals(sharedDataService.getPartyMap().get(userId));
            case "chat.private.out":
                String[] players = headers.getString("id").split("/");
                return players.length == 2 && (players[0].equals(userId) || players[1].equals(userId));
            case "chat.public.out":
                return true;
            default:
                return false;
        }
    }

    @SuppressWarnings("unused")
    private void publishSocketMessage(String address, String handlerId, JsonObject body) {
        JsonObject message = new JsonObject().put("type", "rec");
        message.put("address", address);
        message.put("body", body);
        Buffer buff = Buffer.buffer();
        buff.appendString(message.encode());
        eventBus.publish(handlerId, buff);
    }

    private void publishSocketMessage(String address, String handlerId, JsonArray body) {
        JsonObject message = new JsonObject().put("type", "rec");
        message.put("address", address);
        message.put("body", body);
        Buffer buff = Buffer.buffer();
        buff.appendString(message.encode());
        eventBus.publish(handlerId, buff);
    }

    private void saveMessage(String channel, JsonObject serverResp) {
        JsonObject payload = serverResp.getJsonObject("payload");
        String id;
        JsonArray msgs;
        switch (channel) {
            case "public":
                id = "publicChat";
                msgs = sharedDataService.getMessageMap().get(id);
                if (msgs == null) {
                    msgs = new JsonArray();
                }
                msgs.add(payload);
                if (msgs.size() > 200) {
                    msgs.remove(0);
                }
                sharedDataService.getMessageMap().put(id, msgs);
                break;
            case "private":
                id = serverResp.getString("id");
                if (id == null) {
                    return;
                }
                String sender, receiver;
                sender = id.split("/")[1] + "#private";
                receiver = id.split("/")[0] + "#private";
                log.info("Sender/Receiver - " + sender + "/" + receiver);
                // Save message to sender
                msgs = sharedDataService.getMessageMap().get(sender);
                if (msgs == null) {
                    msgs = new JsonArray();
                }
                msgs.add(payload);
                if (msgs.size() > 200) {
                    msgs.remove(0);
                }
                sharedDataService.getMessageMap().put(sender, msgs);
                // Save message to receiver
                if (sender.equals(receiver)) {
                    return;
                }
                msgs = sharedDataService.getMessageMap().get(receiver);
                if (msgs == null) {
                    msgs = new JsonArray();
                }
                msgs.add(payload);
                if (msgs.size() > 200) {
                    msgs.remove(0);
                }
                sharedDataService.getMessageMap().put(receiver, msgs);
                break;
            default:
                id = serverResp.getString("id");
                log.info("Getting " + channel + " messages for ID: " + id);
                msgs = sharedDataService.getMessageMap().get(id);
                if (msgs == null) {
                    msgs = new JsonArray();
                }
                msgs.add(payload);
                if (msgs.size() > 200) {
                    msgs.remove(0);
                }
                sharedDataService.getMessageMap().put(id, msgs);
        }
    }
}

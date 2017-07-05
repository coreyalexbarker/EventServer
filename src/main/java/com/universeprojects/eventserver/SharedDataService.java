package com.universeprojects.eventserver;

import io.vertx.core.json.JsonArray;
import io.vertx.core.shareddata.LocalMap;
import io.vertx.core.shareddata.SharedData;

public class SharedDataService {
    private final SharedData sd;

    public SharedDataService(SharedData sd) {
        this.sd = sd;
    }

    public LocalMap<String, String> getGroupMap() {
        return sd.<String, String>getLocalMap("groups");
    }

    public LocalMap<String, JsonArray> getMessageMap() {
        return sd.<String, JsonArray>getLocalMap("messages");
    }

    public LocalMap<String, String> getLocationMap() {
        return sd.<String, String>getLocalMap("locations");
    }

    public LocalMap<String, String> getPartyMap() {
        return sd.<String, String>getLocalMap("parties");
    }


    public LocalMap<String, String> getSocketMap() {
        return sd.<String, String>getLocalMap("sockets");
    }
}

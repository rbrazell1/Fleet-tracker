package com.brazell.fleet.cloud;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Subscriber {

    private final ConcurrentHashMap<String, Set<Long>> seenSeqByDevice = new ConcurrentHashMap<>();

    public static void main(String[] args) throws MqttException {
        new Subscriber().run(System.getenv().getOrDefault("MQTT_BROKER_URL", "tcp://mosquitto:1883"));
    }

    public void run(String brokerUrl) throws MqttException {
        MqttClient client = new MqttClient(brokerUrl, "cloud-subscriber", new MemoryPersistence());
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(false);
        opts.setAutomaticReconnect(true);

        client.setCallback(new MqttCallback() {
            @Override public void connectionLost(Throwable cause) {
                System.out.println("Lost connection to broker: " + cause.getMessage());
            }
            @Override public void messageArrived(String topic, MqttMessage message) {
                handle(topic, new String(message.getPayload()));
            }
            @Override public void deliveryComplete(IMqttDeliveryToken token) {}
        });

        client.connect(opts);
        client.subscribe("fleet/+/status", 1);
        client.subscribe("fleet/+/position", 0);
        client.subscribe("fleet/+/boarding", 1);
        System.out.println("Subscriber listening on " + brokerUrl);
    }

    private void handle(String topic, String payload) {
        String[] parts = topic.split("/");
        String deviceId = parts[1], kind = parts[2];

        if (kind.equals("status")) {
            System.out.println("[" + deviceId + "] STATUS -> " + payload);
            return;
        }

        JsonObject obj = JsonParser.parseString(payload).getAsJsonObject();
        long seq = obj.get("seq").getAsLong();
        Set<Long> seen = seenSeqByDevice.computeIfAbsent(deviceId, d -> ConcurrentHashMap.newKeySet());
        boolean isDuplicate = !seen.add(seq);

        System.out.printf("[%s] %s seq=%d %s payload=%s%n",
                deviceId, kind, seq, isDuplicate ? "(DUPLICATE - deduped)" : "", obj);
    }
}

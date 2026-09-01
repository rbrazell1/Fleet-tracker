package com.brazell.fleet.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.sql.SQLException;
import java.util.List;

public class MqttAgent {

    private static final int MAX_BUFFERED_EVENTS = 2500;
    private static final int DRAIN_BATCH_SIZE = 100;

    private final String deviceId;
    private final LocalBuffer buffer;
    private final MqttClient client;

    public MqttAgent(String brokerUrl, String deviceId, LocalBuffer buffer) throws MqttException {
        this.deviceId = deviceId;
        this.buffer = buffer;
        this.client = new MqttClient(brokerUrl, deviceId, new MemoryPersistence());
        
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(false);
        opts.setAutomaticReconnect(true);
        opts.setConnectionTimeout(10);
        opts.setKeepAliveInterval(20);
        opts.setWill("fleet/" + deviceId + "/status", "offline".getBytes(), 1, true);

        client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                System.out.println((reconnect ? "Reconnected" : "Connected") + " to MQTT broker at " + serverURI);
                try {
                    client.publish("fleet/" + deviceId + "/status", "online".getBytes(), 1, true);
                    drainBuffer();
                } catch (MqttException | SQLException e) {
                    System.out.println("Post-connect drain failed: " + e.getMessage());
                }
            }
            @Override 
            public void connectionLost(Throwable cause) {
                System.out.println("Connection lost: " + cause.getMessage());
            }
            @Override
            public void messageArrived(String topic, MqttMessage message){}
            @Override
            public void deliveryComplete(IMqttDeliveryToken token){}
        });
        
        client.connect(opts);
    }

    public void ingest(String type, long deviceUptimeMs, long receivedAtEpochMs, String payloadJson) {
        try {
            long seq = buffer.enqueue(type, deviceUptimeMs, receivedAtEpochMs, payloadJson);
            int dropped = buffer.evictOldestIfOverCapacity(MAX_BUFFERED_EVENTS);
            if (dropped > 0) {
                System.out.println("Buffer over capacity - dropped " + dropped + " oldest unsent events");
            }
            if (client.isConnected()) {
                drainBuffer();
            } else {
                System.out.println("OFFLINE - buffered seq " + seq + " (" + type + ")");
            }
        } catch (MqttException | SQLException e) {
            System.out.println("Ingest failed: " + e.getMessage());
        }
    }

    private synchronized void drainBuffer() throws MqttException, SQLException {
        List<TelemetryEvent> batch;
        while (client.isConnected() && !(batch = buffer.unsent(DRAIN_BATCH_SIZE)).isEmpty()) {
            for (TelemetryEvent e : batch) {
                String topic = "fleet/" + deviceId + "/" + e.type();
                int qos = e.type().equals("Boarding") ? 1 : 0;

                JsonObject obj = JsonParser.parseString(e.payloadJson()).getAsJsonObject();
                obj.addProperty("seq", e.seq());
                obj.addProperty("received_at_epoch_ms", e.receivedAtEpochMs());

                MqttMessage msg = new MqttMessage(obj.toString().getBytes());
                msg.setQos(qos);
                try {
                    client.publish(topic, msg);
                    buffer.markSent(e.seq());
                } catch (MqttException publishEx) {
                    System.err.println(
                            "Publish failed for seq " + e.seq() + ", will retry later: " + publishEx.getMessage());
                    return;
                }
            }
        }
    }

}

package com.brazell.fleet.agent;

import java.time.Instant;

public class FleetAgentApp {
    public static void main(String[] args) throws Exception {
        String serialPort = System.getenv().getOrDefault("SERIAL_PORT", "/dev/ttyUSB0");
        String brokerUrl = System.getenv().getOrDefault("MQTT_BROKER_URL", "localhost:1883"); 
        String deviceId = System.getenv().getOrDefault("DEVICE_ID", "fleet-agent-001");
        String dbPath = System.getenv().getOrDefault("BUFFER_DB_PATH", "data/buffer.db");

        LocalBuffer buffer = new LocalBuffer(dbPath);
        MqttAgent agent = new MqttAgent(brokerUrl, deviceId, buffer);

        System.out.println("Fleet agent'" + deviceId + "' starting. Listening on serial port: " + serialPort
                + ", MQTT broker: " + brokerUrl);

        new SerialReader(serialPort, 115200).run(json -> {
            String type = json.get("type").getAsString();
            long deviceUptimeMs = json.get("device_uptime_ms").getAsLong();
            agent.ingest(type, deviceUptimeMs, Instant.now().toEpochMilli(), json.toString());
        });
    }
}

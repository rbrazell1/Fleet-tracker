package com.brazell.fleet.agent;

import com.fazecast.jSerialComm.SerialPort;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class SerialReader {
    
    private final String portDescription;
    private final int baudRate;

    public SerialReader(String portDescription, int baudRate) {
        this.portDescription = portDescription;
        this.baudRate = baudRate;
    }

    public void run(Consumer<JsonObject> onLine) throws IOException {
        SerialPort port = SerialPort.getCommPort(portDescription);
        port.setBaudRate(baudRate);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0);
        
        if (!port.openPort()) {
            throw new IllegalStateException("Failed to open serial port: " + portDescription);
        }

        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(port.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    try {
                        onLine.accept(JsonParser.parseString(line).getAsJsonObject());
                    } catch (Exception e) {
                        System.out.println("Failed to parse line as JSON: " + line);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Error reading from serial port: " + portDescription, e);
            } finally {
                port.closePort();
            }
    }
}

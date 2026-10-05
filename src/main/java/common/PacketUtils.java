package common;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * PacketUtils provides helper methods to build, encode, decode, and transmit 
 * UDP DatagramPackets using UTF-8 and strict packet size validation.
 */
public final class PacketUtils {

    private PacketUtils() {
        // Prevent instantiation
    }

    /**
     * Creates a DatagramPacket for outgoing transmission.
     * Throws IllegalArgumentException if the encoded payload exceeds MAX_PACKET_SIZE.
     */
    public static DatagramPacket createPacket(String message, InetAddress address, int port) {
        if (message == null) {
            message = "";
        }
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Protocol.MAX_PACKET_SIZE) {
            throw new IllegalArgumentException("Payload size (" + bytes.length + 
                    " bytes) exceeds MAX_PACKET_SIZE (" + Protocol.MAX_PACKET_SIZE + " bytes)");
        }
        return new DatagramPacket(bytes, bytes.length, address, port);
    }

    /**
     * Extracts a UTF-8 String message from an incoming DatagramPacket.
     */
    public static String extractMessage(DatagramPacket packet) {
        if (packet == null || packet.getData() == null || packet.getLength() == 0) {
            return "";
        }
        return new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
    }

    /**
     * Creates an empty incoming buffer packet ready to receive data up to MAX_PACKET_SIZE.
     */
    public static DatagramPacket createReceivePacket() {
        byte[] buffer = new byte[Protocol.MAX_PACKET_SIZE];
        return new DatagramPacket(buffer, buffer.length);
    }

    /**
     * Sends a message string to the specified destination via the provided DatagramSocket.
     */
    public static void send(DatagramSocket socket, String message, InetAddress address, int port) throws IOException {
        DatagramPacket packet = createPacket(message, address, port);
        socket.send(packet);
    }
}

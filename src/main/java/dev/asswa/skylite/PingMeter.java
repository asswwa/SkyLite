package dev.asswa.skylite;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Real latency to the server the player is connected to. Hypixel spoofs the tab-list ping on
 * game servers (it always reads "1"), so instead of trusting the tab we send the same tiny
 * status request the multiplayer server list uses — to the same address, from a background
 * daemon thread, every few seconds — and measure the round trip ourselves. No mixins, no
 * packets of our own beyond one standard status ping per cycle.
 */
public final class PingMeter {
    private static final int INTERVAL_MS = 3000;
    private static final int TIMEOUT_MS = 2500;
    private static final int DEFAULT_PORT = 25565;

    private volatile String target; // "host" or "host:port"; null when not on a server
    private volatile int ping = -1; // -1 = no data yet
    private boolean started;

    /** Latest measured round trip in milliseconds; -1 until the first measurement lands. */
    public int ping() {
        return ping;
    }

    /**
     * Tells the meter where the player is connected (called from the client thread).
     * {@code target} is {@code host} or {@code host:port}; null disconnects the meter.
     */
    public void updateTarget(String target) {
        this.target = target;
        if (target == null) {
            ping = -1;
            return;
        }
        if (!started) {
            started = true;
            Thread thread = new Thread(this::loop, "SkyLite ping meter");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private void loop() {
        while (true) {
            String target = this.target;
            if (target != null) {
                int measured = measure(target);
                if (measured > 0) {
                    ping = measured; // a failed cycle keeps the last good value
                }
            }
            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static int measure(String hostPort) {
        String host = hostPort;
        int port = DEFAULT_PORT;
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0 && hostPort.indexOf(':') == colon) { // exactly one colon: host:port
            try {
                port = Integer.parseInt(hostPort.substring(colon + 1));
                host = hostPort.substring(0, colon);
            } catch (NumberFormatException ignored) {
                port = DEFAULT_PORT; // not a port — treat the whole string as the host
            }
        }
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(TIMEOUT_MS);
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);

            OutputStream out = socket.getOutputStream();
            byte[] handshake = handshake(host, port);
            writeVarInt(out, handshake.length);
            out.write(handshake);
            writeVarInt(out, 1); // status request: length 1 …
            out.write(0x00);     // … packet id 0x00
            out.flush();

            long start = System.nanoTime();
            if (socket.getInputStream().read() < 0) {
                return -1;
            }
            return (int) Math.max(0, (System.nanoTime() - start) / 1_000_000L);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    /** Handshake packet with next-state = status (the same request the server list makes). */
    private static byte[] handshake(String host, int port) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        writeVarInt(out, 0x00); // packet id: handshake
        writeVarInt(out, -1);   // protocol version: any is accepted in the status state
        writeString(out, host);
        out.writeShort(port);
        writeVarInt(out, 1);    // next state: status
        return bytes.toByteArray();
    }

    private static void writeString(OutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }
}

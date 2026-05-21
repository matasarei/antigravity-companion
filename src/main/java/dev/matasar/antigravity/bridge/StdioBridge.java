package dev.matasar.antigravity.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Tiny stdio &lt;-&gt; TCP relay used by the MCP bridge scripts the plugin generates.
 *
 * <p>The plugin runs an MCP server on a loopback TCP port; the Antigravity CLI ({@code agy})
 * connects to MCP servers over stdio. The bridge script spawned by {@code agy} invokes this
 * class so that {@code agy}'s stdio is wired straight to the plugin's TCP server, with no
 * dependency on platform tools like {@code nc}.
 *
 * <p>Zero dependencies, pure {@code java.io} + {@code java.net} — runs anywhere a JBR runs.
 */
public final class StdioBridge {

    private static final int BUFFER_SIZE = 8192;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: StdioBridge <host> <port>");
            System.exit(2);
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);

        try (Socket socket = new Socket(host, port)) {
            socket.setTcpNoDelay(true);
            InputStream socketIn = socket.getInputStream();
            OutputStream socketOut = socket.getOutputStream();

            Thread socketToStdout = new Thread(
                () -> pipe(socketIn, System.out),
                "antigravity-bridge-recv"
            );
            socketToStdout.setDaemon(true);
            socketToStdout.start();

            pipe(System.in, socketOut);
        }
    }

    private static void pipe(InputStream in, OutputStream out) {
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            while (true) {
                int read = in.read(buffer);
                if (read < 0) break;
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException ignored) {
            // Peer closed — fine.
        }
    }

    private StdioBridge() {}
}

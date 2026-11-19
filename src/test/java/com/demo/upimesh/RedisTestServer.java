package com.demo.upimesh;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Owns only a uniquely named disposable container and its anonymous data volume. */
final class RedisTestServer implements AutoCloseable {
    private final String name = "upimesh-redis-it-" + UUID.randomUUID().toString().replace("-", "");
    private boolean created;
    private int port;

    static RedisTestServer start() throws Exception {
        RedisTestServer server = new RedisTestServer();
        try {
            try (var reservation = new java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))) {
                server.port = reservation.getLocalPort();
            }
            server.docker("run", "-d", "--name", server.name, "-p", "127.0.0.1:" + server.port + ":6379",
                    "redis:8.10.3", "redis-server", "--appendonly", "yes", "--appendfsync", "everysec",
                    "--maxmemory-policy", "noeviction");
            server.created = true;
            String binding = server.docker("port", server.name, "6379/tcp").trim();
            if (server.port != Integer.parseInt(binding.substring(binding.lastIndexOf(':') + 1)))
                throw new IllegalStateException("Unexpected test port mapping");
            server.ready();
            return server;
        } catch (Exception e) { server.close(); throw e; }
    }

    int port() { return port; }
    void stop() throws Exception { docker("stop", name); }
    void resume() throws Exception { docker("start", name); ready(); }
    void restart() throws Exception { docker("restart", name); ready(); }

    private void ready() throws Exception {
        for (int i = 0; i < 20; i++) {
            if (docker("exec", name, "redis-cli", "ping").contains("PONG")) return;
            Thread.sleep(100);
        }
        throw new IllegalStateException("Test Redis did not become ready");
    }

    private String docker(String... arguments) throws Exception {
        var command = new ArrayList<String>();
        command.add("docker"); command.addAll(Arrays.asList(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly(); throw new IllegalStateException("Docker test command timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) throw new IllegalStateException("Docker test command failed: " + output);
        return output;
    }

    @Override
    public void close() throws Exception {
        if (created && name.matches("upimesh-redis-it-[0-9a-f]{32}")) {
            docker("rm", "-f", "-v", name);
            created = false;
        }
    }
}

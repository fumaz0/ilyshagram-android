package org.telegram.messenger;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class PluginDevServer {

    private static final int PORT = 42690;
    private static volatile PluginDevServer instance;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private boolean running;

    private PluginDevServer() {}

    public static PluginDevServer getInstance() {
        if (instance == null) {
            synchronized (PluginDevServer.class) {
                if (instance == null) {
                    instance = new PluginDevServer();
                }
            }
        }
        return instance;
    }

    public void start() {
        if (running) return;
        running = true;
        serverThread = new Thread(this::run, "PluginDevServer");
        serverThread.setDaemon(true);
        serverThread.start();
        FileLog.e("PluginDevServer: Started on port " + PORT);
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception e) {
            // ignore
        }
        FileLog.e("PluginDevServer: Stopped");
    }

    public boolean isRunning() {
        return running;
    }

    private void run() {
        try {
            serverSocket = new ServerSocket(PORT);
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    new Thread(() -> handleClient(client), "DevClient").start();
                } catch (Exception e) {
                    if (running) {
                        FileLog.e("PluginDevServer: Accept error", e);
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e("PluginDevServer: Failed to start", e);
            running = false;
        }
    }

    private void handleClient(Socket client) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            PrintWriter writer = new PrintWriter(client.getOutputStream(), true);

            String line;
            while ((line = reader.readLine()) != null && running) {
                try {
                    JSONObject request = new JSONObject(line);
                    String action = request.optString("action", "");
                    JSONObject response = handleCommand(action, request);
                    writer.println(response.toString());
                } catch (Exception e) {
                    JSONObject error = new JSONObject();
                    error.put("error", e.getMessage());
                    writer.println(error.toString());
                }
            }
            client.close();
        } catch (Exception e) {
            // client disconnected
        }
    }

    private JSONObject handleCommand(String action, JSONObject request) throws Exception {
        JSONObject response = new JSONObject();
        PluginManager manager = PluginManager.getInstance();

        switch (action) {
            case "ping":
                response.put("status", "ok");
                response.put("version", "1.0.0");
                break;

            case "get_plugins":
                org.json.JSONArray arr = new org.json.JSONArray();
                for (PluginManager.PluginInfo p : manager.getPlugins()) {
                    JSONObject pObj = new JSONObject();
                    pObj.put("id", p.id);
                    pObj.put("name", p.name);
                    pObj.put("description", p.description);
                    pObj.put("author", p.author);
                    pObj.put("version", p.version);
                    pObj.put("enabled", manager.isPluginEnabled(p.id));
                    arr.put(pObj);
                }
                response.put("plugins", arr);
                break;

            case "enable_plugin":
                String enableId = request.getString("plugin_id");
                manager.enablePlugin(enableId);
                response.put("status", "ok");
                break;

            case "disable_plugin":
                String disableId = request.getString("plugin_id");
                manager.disablePlugin(disableId);
                response.put("status", "ok");
                break;

            case "reload_plugin":
                String reloadId = request.getString("plugin_id");
                manager.unloadPlugin(reloadId);
                PluginManager.PluginInfo reloadInfo = null;
                for (PluginManager.PluginInfo p : manager.getPlugins()) {
                    if (p.id.equals(reloadId)) {
                        reloadInfo = p;
                        break;
                    }
                }
                if (reloadInfo != null) {
                    manager.loadPlugin(reloadInfo);
                    response.put("status", "ok");
                } else {
                    response.put("error", "Plugin not found");
                }
                break;

            case "write_plugin":
                String writeId = request.getString("plugin_id");
                String content = request.getString("content");
                PluginManager.PluginInfo installed = manager.installPlugin(content, writeId);
                if (installed != null) {
                    response.put("status", "ok");
                    response.put("id", installed.id);
                } else {
                    response.put("error", "Failed to install plugin");
                }
                break;

            case "remove_plugin":
                String removeId = request.getString("plugin_id");
                manager.deletePlugin(removeId);
                response.put("status", "ok");
                break;

            case "get_plugin_state":
                String stateId = request.getString("plugin_id");
                response.put("enabled", manager.isPluginEnabled(stateId));
                Object loaded = manager.getLoadedPlugin(stateId);
                response.put("loaded", loaded != null);
                break;

            default:
                response.put("error", "Unknown action: " + action);
                break;
        }
        return response;
    }
}

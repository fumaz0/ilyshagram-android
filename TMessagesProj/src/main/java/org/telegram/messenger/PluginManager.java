package org.telegram.messenger;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class PluginManager {

    private static volatile PluginManager instance;
    private boolean initialized;
    private Object python;
    private final ArrayList<PluginInfo> plugins = new ArrayList<>();
    private final HashMap<String, Boolean> enabledState = new HashMap<>();
    private final HashMap<String, Object> loadedModules = new HashMap<>();
    private final ArrayList<PluginListener> listeners = new ArrayList<>();

    private Class<?> pythonClass;
    private Class<?> platformClass;
    private Method isStartedMethod;
    private Method startMethod;
    private Method getInstanceMethod;
    private Method getModuleMethod;

    private Class<?> pyObjectClass;
    private Method callAttr2Method;
    private Method callAttr3Method;
    private Method attrMethod;
    private Method getAttrMethod;
    private Method toJavaMethod;

    public interface PluginListener {
        void onPluginLoaded(String pluginId);
        void onPluginUnloaded(String pluginId);
        void onPluginError(String pluginId, String error);
    }

    public static class PluginInfo {
        public String filePath;
        public String id;
        public String name;
        public String description;
        public String author;
        public String version;
        public String fileName;
        public boolean enabled = true;
    }

    public static PluginManager getInstance() {
        if (instance == null) {
            synchronized (PluginManager.class) {
                if (instance == null) {
                    instance = new PluginManager();
                }
            }
        }
        return instance;
    }

    public void init(Context context) {
        if (initialized) return;
        synchronized (PluginManager.class) {
            if (initialized) return;
            try {
                pythonClass = Class.forName("com.chaquo.python.Python");
                platformClass = Class.forName("com.chaquo.python.android.AndroidPlatform");
                pyObjectClass = Class.forName("com.chaquo.python.PyObject");

                isStartedMethod = platformClass.getMethod("isStarted");
                startMethod = platformClass.getMethod("start", Context.class);
                getInstanceMethod = pythonClass.getMethod("getInstance");
                getModuleMethod = pythonClass.getMethod("getModule", String.class);

                callAttr2Method = pyObjectClass.getMethod("callAttr", String.class, Object[].class);
                callAttr3Method = pyObjectClass.getMethod("callAttr", String.class, boolean.class, Object[].class);
                attrMethod = pyObjectClass.getMethod("attr", String.class);
                getAttrMethod = pyObjectClass.getMethod("getAttr", String.class);
                toJavaMethod = pyObjectClass.getMethod("toJava", Class.class);

                if (!(Boolean) isStartedMethod.invoke(null)) {
                    startMethod.invoke(null, context);
                }
                python = getInstanceMethod.invoke(null);
                initialized = true;
                copyExamplePlugin();
                loadPlugins();
            } catch (ClassNotFoundException e) {
                FileLog.e("PluginManager: Chaquopy not available", e);
            } catch (Exception e) {
                FileLog.e("PluginManager: Failed to init Python", e);
            }
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public Object getPython() {
        return python;
    }

    private void copyExamplePlugin() {
        try {
            Context context = ApplicationLoader.applicationContext;
            File pluginsDir = new File(context.getFilesDir(), "plugins");
            pluginsDir.mkdirs();
            File exampleFile = new File(pluginsDir, "example.plugin");
            if (!exampleFile.exists()) {
                String exampleCode = "from base_plugin import BasePlugin, HookResult, HookStrategy\n\n" +
                    "__id__ = \"example_plugin\"\n" +
                    "__name__ = \"Example Plugin\"\n" +
                    "__description__ = \"\\u041f\\u0440\\u0438\\u043c\\u0435\\u0440 \\u043f\\u043b\\u0430\\u0433\\u0438\\u043d\\u0430 \\u0434\\u043b\\u044f ilyshagram\"\n" +
                    "__author__ = \"ilyshagram\"\n" +
                    "__version__ = \"1.0.0\"\n\n\n" +
                    "class Plugin(BasePlugin):\n" +
                    "    def on_plugin_load(self):\n" +
                    "        self.log(\"Example plugin loaded!\")\n" +
                    "        self.add_on_send_message_hook()\n\n" +
                    "    def on_send_message_hook(self, account, message):\n" +
                    "        if message.startswith(\".hello\"):\n" +
                    "            name = message.split(\" \", 1)[1] if \" \" in message else \"World\"\n" +
                    "            return HookResult(\n" +
                    "                strategy=HookStrategy.MODIFY,\n" +
                    "                message=f\"Hello, {name}!\"\n" +
                    "            )\n" +
                    "        return HookResult()\n";
                FileOutputStream fos = new FileOutputStream(exampleFile);
                fos.write(exampleCode.getBytes("UTF-8"));
                fos.close();
            }
        } catch (Exception e) {
            // ignore
        }
    }

    public void loadPlugins() {
        plugins.clear();
        Context context = ApplicationLoader.applicationContext;
        if (context == null) return;

        File pluginsDir = new File(context.getFilesDir(), "plugins");
        if (!pluginsDir.exists()) {
            pluginsDir.mkdirs();
            return;
        }

        File[] files = pluginsDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.getName().endsWith(".plugin")) {
                PluginInfo info = parsePluginFile(file);
                if (info != null) {
                    plugins.add(info);
                }
            }
        }

        loadEnabledState();
        for (PluginInfo plugin : plugins) {
            if (plugin.enabled && Boolean.TRUE.equals(enabledState.getOrDefault(plugin.id, true))) {
                loadPlugin(plugin);
            }
        }
    }

    public PluginInfo parsePluginFile(File file) {
        PluginInfo info = new PluginInfo();
        info.filePath = file.getAbsolutePath();
        info.fileName = file.getName();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("__id__")) {
                    info.id = extractStringValue(line);
                } else if (line.startsWith("__name__")) {
                    info.name = extractStringValue(line);
                } else if (line.startsWith("__description__")) {
                    info.description = extractStringValue(line);
                } else if (line.startsWith("__author__")) {
                    info.author = extractStringValue(line);
                } else if (line.startsWith("__version__")) {
                    info.version = extractStringValue(line);
                }
            }
        } catch (IOException e) {
            return null;
        }

        if (info.id == null || info.id.isEmpty()) {
            info.id = file.getName().replace(".plugin", "");
        }
        if (info.name == null || info.name.isEmpty()) {
            info.name = info.id;
        }

        return info;
    }

    private String extractStringValue(String line) {
        int eqIndex = line.indexOf('=');
        if (eqIndex < 0) return null;

        String value = line.substring(eqIndex + 1).trim();
        if (value.length() >= 2 && value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\'') {
            return value.substring(1, value.length() - 1);
        }
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    public boolean loadPlugin(PluginInfo plugin) {
        if (python == null) return false;
        try {
            File pluginsDir = new File(ApplicationLoader.applicationContext.getFilesDir(), "plugins");
            Object osModule = getModuleMethod.invoke(python, "os");
            callAttr2Method.invoke(osModule, "chdir", new Object[]{pluginsDir.getAbsolutePath()});

            String importName = plugin.fileName.replace(".plugin", "");
            Object importlib = getModuleMethod.invoke(python, "importlib");
            Object module = callAttr2Method.invoke(importlib, "import_module", new Object[]{importName});

            Object pluginClass = attrMethod.invoke(module, "Plugin");
            Object instance = callAttr2Method.invoke(pluginClass, "__init__", new Object[]{});
            loadedModules.put(plugin.id, instance);
            callAttr2Method.invoke(instance, "on_plugin_load", new Object[]{});

            for (PluginListener listener : listeners) {
                listener.onPluginLoaded(plugin.id);
            }

            FileLog.e("PluginManager: Loaded plugin " + plugin.id);
            return true;
        } catch (Exception e) {
            FileLog.e("PluginManager: Failed to load plugin " + plugin.id, e);
            for (PluginListener listener : listeners) {
                listener.onPluginError(plugin.id, e.getMessage());
            }
            return false;
        }
    }

    public void unloadPlugin(String pluginId) {
        Object plugin = loadedModules.get(pluginId);
        if (plugin != null && python != null) {
            try {
                callAttr2Method.invoke(plugin, "on_plugin_unload", new Object[]{});
            } catch (Exception e) {
                // ignore
            }
            loadedModules.remove(pluginId);
        }
        for (PluginListener listener : listeners) {
            listener.onPluginUnloaded(pluginId);
        }
    }

    public void enablePlugin(String pluginId) {
        enabledState.put(pluginId, true);
        saveEnabledState();
        PluginInfo info = findPlugin(pluginId);
        if (info != null) {
            info.enabled = true;
            loadPlugin(info);
        }
    }

    public void disablePlugin(String pluginId) {
        enabledState.put(pluginId, false);
        saveEnabledState();
        unloadPlugin(pluginId);
        PluginInfo info = findPlugin(pluginId);
        if (info != null) {
            info.enabled = false;
        }
    }

    public void deletePlugin(String pluginId) {
        unloadPlugin(pluginId);
        PluginInfo info = findPlugin(pluginId);
        if (info != null) {
            File file = new File(info.filePath);
            file.delete();
            plugins.remove(info);
        }
        enabledState.remove(pluginId);
        saveEnabledState();
    }

    public PluginInfo installPlugin(String code, String fileName) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) return null;

        File pluginsDir = new File(context.getFilesDir(), "plugins");
        pluginsDir.mkdirs();

        if (!fileName.endsWith(".plugin")) {
            fileName = fileName + ".plugin";
        }
        File file = new File(pluginsDir, fileName);
        try {
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(code.getBytes("UTF-8"));
            fos.close();
        } catch (IOException e) {
            return null;
        }

        PluginInfo info = parsePluginFile(file);
        if (info != null) {
            plugins.add(info);
            enabledState.put(info.id, true);
            saveEnabledState();
            loadPlugin(info);
        }
        return info;
    }

    public PluginInfo installPluginFromUrl(String url) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            if (conn.getResponseCode() == 200) {
                java.io.InputStream is = conn.getInputStream();
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] temp = new byte[4096];
                int bytesRead;
                while ((bytesRead = is.read(temp)) != -1) {
                    buffer.write(temp, 0, bytesRead);
                }
                byte[] data = buffer.toByteArray();
                is.close();
                conn.disconnect();

                String code = new String(data, "UTF-8");
                String fileName = "plugin_" + System.currentTimeMillis();
                return installPlugin(code, fileName);
            }
            conn.disconnect();
        } catch (Exception e) {
            FileLog.e("PluginManager: Failed to install from URL", e);
        }
        return null;
    }

    public String executeOnSendMessageHook(int account, String message) {
        for (Map.Entry<String, Object> entry : loadedModules.entrySet()) {
            try {
                Object result = callAttr2Method.invoke(entry.getValue(),
                    "on_send_message_hook", new Object[]{account, message}
                );
                Object strategyObj = getAttrMethod.invoke(result, "strategy");
                String strategy = strategyObj.toString();
                if (strategy.equals("modify")) {
                    Object msgObj = getAttrMethod.invoke(result, "message");
                    return msgObj.toString();
                } else if (strategy.equals("cancel")) {
                    return null;
                }
            } catch (Exception e) {
                FileLog.e("PluginManager: Hook error in " + entry.getKey(), e);
            }
        }
        return message;
    }

    public String executeOnReceiveMessageHook(int account, String message) {
        for (Map.Entry<String, Object> entry : loadedModules.entrySet()) {
            try {
                Object result = callAttr2Method.invoke(entry.getValue(),
                    "on_receive_message_hook", new Object[]{account, message}
                );
                Object strategyObj = getAttrMethod.invoke(result, "strategy");
                String strategy = strategyObj.toString();
                if (strategy.equals("modify")) {
                    Object msgObj = getAttrMethod.invoke(result, "message");
                    return msgObj.toString();
                } else if (strategy.equals("cancel")) {
                    return null;
                }
            } catch (Exception e) {
                FileLog.e("PluginManager: Hook error in " + entry.getKey(), e);
            }
        }
        return message;
    }

    public ArrayList<PluginInfo> getPlugins() {
        return new ArrayList<>(plugins);
    }

    public boolean isPluginEnabled(String pluginId) {
        return Boolean.TRUE.equals(enabledState.getOrDefault(pluginId, true));
    }

    public Object getLoadedPlugin(String pluginId) {
        return loadedModules.get(pluginId);
    }

    private PluginInfo findPlugin(String pluginId) {
        for (PluginInfo p : plugins) {
            if (p.id.equals(pluginId)) return p;
        }
        return null;
    }

    private void loadEnabledState() {
        try {
            Context context = ApplicationLoader.applicationContext;
            File file = new File(context.getFilesDir(), "plugin_state.json");
            if (!file.exists()) return;

            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            JSONObject json = new JSONObject(sb.toString());
            for (PluginInfo p : plugins) {
                if (json.has(p.id)) {
                    enabledState.put(p.id, json.getBoolean(p.id));
                    p.enabled = json.getBoolean(p.id);
                }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private void saveEnabledState() {
        try {
            Context context = ApplicationLoader.applicationContext;
            JSONObject json = new JSONObject();
            for (PluginInfo p : plugins) {
                json.put(p.id, enabledState.getOrDefault(p.id, true));
            }
            File file = new File(context.getFilesDir(), "plugin_state.json");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(json.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            // ignore
        }
    }

    public void addListener(PluginListener listener) {
        listeners.add(listener);
    }

    public void removeListener(PluginListener listener) {
        listeners.remove(listener);
    }

    public void installPluginFromFile(File file) {
        PluginInfo info = parsePluginFile(file);
        if (info != null) {
            plugins.add(info);
            enabledState.put(info.id, true);
            saveEnabledState();
            loadPlugin(info);
        }
    }
}

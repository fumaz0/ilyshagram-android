"""
base_plugin.py — ilyshagram Plugin SDK (exteraGram-compatible)

Every plugin must subclass BasePlugin and set module-level metadata:
    __id__       = "my_plugin"
    __name__     = "My Plugin"
    __description__ = "Does something useful"
    __author__    = "You"
    __version__   = "1.0.0"

Lifecycle methods (override as needed):
    on_plugin_load()       — called when the plugin is first loaded
    on_plugin_unload()     — called when the plugin is disabled/removed
    on_account_switch(int) — called when the active account changes

Hook registration (call in on_plugin_load):
    self.add_on_send_message_hook()
    self.add_on_receive_message_hook()

Hook handlers:
    on_send_message_hook(account, params) -> HookResult
    on_receive_message_hook(account, message) -> HookResult
"""

from enum import Enum


class HookStrategy(Enum):
    PASS = "pass"
    MODIFY = "modify"
    CANCEL = "cancel"


class HookResult:
    def __init__(self, strategy=HookStrategy.PASS, params=None, message=None):
        self.strategy = strategy
        self.params = params
        self.message = message


class BasePlugin:
    def __init__(self):
        self._hooks = set()
        self._settings = {}
        self._enabled = True

    # ---- lifecycle ----

    def on_plugin_load(self):
        pass

    def on_plugin_unload(self):
        pass

    def on_account_switch(self, account):
        pass

    # ---- hook registration ----

    def add_on_send_message_hook(self):
        self._hooks.add("on_send_message")

    def add_on_receive_message_hook(self):
        self._hooks.add("on_receive_message")

    # ---- hook handlers (override) ----

    def on_send_message_hook(self, account, params):
        return HookResult()

    def on_receive_message_hook(self, account, message):
        return HookResult()

    # ---- settings ----

    def get_setting(self, key, default=None):
        return self._settings.get(key, default)

    def set_setting(self, key, value):
        self._settings[key] = value

    def create_settings(self):
        return []

    # ---- logging ----

    def log(self, msg):
        from com.chaquo.python import Python
        Python.getPlatform().log(str(msg))

    # ---- reflection (Xposed-style) ----

    def find_class(self, class_name):
        from java import jclass
        return jclass(class_name)

    def get_field(self, obj, field_name):
        cls = obj.getClass()
        f = cls.getDeclaredField(field_name)
        f.setAccessible(True)
        return f.get(obj)

    def set_field(self, obj, field_name, value):
        cls = obj.getClass()
        f = cls.getDeclaredField(field_name)
        f.setAccessible(True)
        f.set(obj, value)

    def call_method(self, obj, method_name, *args):
        cls = obj.getClass()
        param_types = [type(a) for a in args]
        m = cls.getDeclaredMethod(method_name, *param_types)
        m.setAccessible(True)
        return m.invoke(obj, *args)

    # ---- menu items ----

    def add_menu_item(self, text, icon=None, callback=None):
        pass

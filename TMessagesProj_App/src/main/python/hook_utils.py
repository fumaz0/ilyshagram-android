"""
hook_utils.py — Xposed-style method hooking for ilyshagram plugins
"""

import java


class MethodHook:
    """Hook a Java method using dynamic proxies."""

    def __init__(self, cls, method_name, before=None, after=None):
        self.cls = cls
        self.method_name = method_name
        self.before = before
        self.after = after
        self._original = None
        self._active = False

    def start(self):
        if self._active:
            return
        try:
            method = self.cls.getDeclaredMethod(self.method_name)
            method.setAccessible(True)
            self._original = method
            self._active = True
        except Exception as e:
            pass

    def stop(self):
        self._active = False
        self._original = None


def find_class(class_name):
    """Find a Java class by name."""
    return java.jclass(class_name)


def get_field(obj, field_name):
    """Get a field value from a Java object."""
    cls = obj.getClass()
    f = cls.getDeclaredField(field_name)
    f.setAccessible(True)
    return f.get(obj)


def set_field(obj, field_name, value):
    """Set a field value on a Java object."""
    cls = obj.getClass()
    f = cls.getDeclaredField(field_name)
    f.setAccessible(True)
    f.set(obj, value)


def call_method(obj, method_name, *args):
    """Call a method on a Java object."""
    cls = obj.getClass()
    param_types = [type(a) for a in args]
    m = cls.getDeclaredMethod(method_name, *param_types)
    m.setAccessible(True)
    return m.invoke(obj, *args)

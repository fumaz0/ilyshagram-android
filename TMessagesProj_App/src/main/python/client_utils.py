"""
client_utils.py — Client utilities for ilyshagram plugins
"""

import threading


def run_on_ui(fn):
    """Run a function on the Android UI thread."""
    from com.chaquo.python import Python
    activity = Python.getPlatform().getApplication()
    if activity:
        from android.os import Handler, Looper
        handler = Handler(Looper.getMainLooper())
        handler.post(lambda: fn())
    else:
        fn()


def run_on_queue(fn, queue_name="default"):
    """Run a function in a background thread."""
    t = threading.Thread(target=fn, daemon=True, name=f"plugin-{queue_name}")
    t.start()


def get_application():
    """Get the Android Application context."""
    from com.chaquo.python import Python
    return Python.getPlatform().getApplication()

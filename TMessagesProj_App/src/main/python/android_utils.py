"""
android_utils.py — Android UI helpers for ilyshagram plugins
"""

import threading


def run_on_ui(fn):
    """Run a function on the Android UI thread."""
    from com.chaquo.python import Python
    app = Python.getPlatform().getApplication()
    if app:
        from android.os import Handler, Looper
        handler = Handler(Looper.getMainLooper())
        handler.post(lambda: fn())


def toast(text):
    """Show a toast message."""
    def _show():
        from com.chaquo.python import Python
        app = Python.getPlatform().getApplication()
        if app:
            from android.widget import Toast
            Toast.makeText(app, str(text), Toast.LENGTH_SHORT).show()
    run_on_ui(_show)


def log(tag, msg):
    """Log a message to Android logcat."""
    from android.util import Log
    Log.d(str(tag), str(msg))

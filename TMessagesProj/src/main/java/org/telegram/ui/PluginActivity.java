package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PluginManager;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

public class PluginActivity extends BaseFragment {

    private static final int ID_INSTALL = 1;
    private static final int ID_DEV_MODE = 2;

    private UniversalRecyclerView listView;
    private boolean developerMode;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Плагины");

        actionBar.createMenu().addItem(ID_INSTALL, R.drawable.msg_add);
        actionBar.createMenu().addItem(ID_DEV_MODE, R.drawable.msg_settings);

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == ID_INSTALL) {
                    showInstallDialog();
                } else if (id == ID_DEV_MODE) {
                    developerMode = !developerMode;
                    updateList();
                }
            }
        });

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, this::onLongClick);
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));

        PluginManager manager = PluginManager.getInstance();
        if (!manager.isInitialized()) {
            manager.init(ApplicationLoader.applicationContext);
        }

        return fragmentView = contentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        updateList();
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        PluginManager manager = PluginManager.getInstance();
        ArrayList<PluginManager.PluginInfo> plugins = manager.getPlugins();

        items.add(UItem.asHeader(LocaleController.getString(R.string.Plugins)));

        if (!manager.isInitialized()) {
            items.add(UItem.asShadow("Инициализация движка плагинов..."));
            return;
        }

        if (plugins.isEmpty()) {
            items.add(UItem.asShadow("Нет установленных плагинов.\n\nНажмите + чтобы установить плагин."));
        } else {
            for (int i = 0; i < plugins.size(); i++) {
                PluginManager.PluginInfo plugin = plugins.get(i);
                StringBuilder subtitle = new StringBuilder();
                if (plugin.author != null && !plugin.author.isEmpty()) {
                    subtitle.append(plugin.author);
                }
                if (plugin.version != null && !plugin.version.isEmpty()) {
                    if (subtitle.length() > 0) subtitle.append(" \u00b7 ");
                    subtitle.append("v").append(plugin.version);
                }
                if (plugin.description != null && !plugin.description.isEmpty()) {
                    if (subtitle.length() > 0) subtitle.append("\n");
                    subtitle.append(plugin.description);
                }
                items.add(UItem.asCheck(i, plugin.name).setChecked(manager.isPluginEnabled(plugin.id)));
            }
        }

        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("Режим разработчика"));
        items.add(UItem.asCheck(10001, "Developer Mode").setChecked(developerMode));

        if (developerMode) {
            items.add(UItem.asShadow("Плагины — Python-скрипты (.plugin файлы).\nПапка: /files/plugins/\nДвижок: Chaquopy Python 3.11"));
            items.add(UItem.asButton(10002, R.drawable.msg_settings, "Перезагрузить плагины"));
            items.add(UItem.asButton(10003, R.drawable.msg_settings, "Очистить все плагины"));
        }

        items.add(UItem.asShadow("Плагины используют Python SDK exteraGram.\nПоддерживаются хуки, рефлексия, UI."));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        PluginManager manager = PluginManager.getInstance();
        ArrayList<PluginManager.PluginInfo> plugins = manager.getPlugins();

        if (item.viewType == UniversalAdapter.VIEW_TYPE_CHECK) {
            int index = item.id;
            if (index == 10001) {
                developerMode = !developerMode;
                updateList();
                return;
            }
            if (index < 0 || index >= plugins.size()) return;
            PluginManager.PluginInfo plugin = plugins.get(index);
            if (manager.isPluginEnabled(plugin.id)) {
                manager.disablePlugin(plugin.id);
            } else {
                manager.enablePlugin(plugin.id);
            }
            updateList();
        } else if (item.id == 10002) {
            manager.loadPlugins();
            updateList();
            Toast.makeText(getParentActivity(), "Плагины перезагружены", Toast.LENGTH_SHORT).show();
        } else if (item.id == 10003) {
            showClearAllDialog();
        }
    }

    private boolean onLongClick(UItem item, View view, int position, float x, float y) {
        PluginManager manager = PluginManager.getInstance();
        ArrayList<PluginManager.PluginInfo> plugins = manager.getPlugins();

        if (item.viewType == UniversalAdapter.VIEW_TYPE_CHECK) {
            int index = item.id;
            if (index < 0 || index >= plugins.size()) return false;
            PluginManager.PluginInfo plugin = plugins.get(index);
            showPluginDetailsDialog(plugin);
            return true;
        }
        return false;
    }

    private void showInstallDialog() {
        if (getParentActivity() == null) return;

        final Context context = getParentActivity();
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Установить плагин");

        final FrameLayout container = new FrameLayout(context);
        final android.widget.EditText input = new android.widget.EditText(context);
        input.setHint("Вставьте URL или Python-код плагина");
        input.setMinLines(5);
        input.setMaxLines(15);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        builder.setView(container);
        builder.setPositiveButton("Установить", (dialog, which) -> {
            String text = input.getText().toString().trim();
            if (!text.isEmpty()) {
                installPlugin(text);
            }
        });
        builder.setNegativeButton("Отмена", null);
        builder.show();
    }

    private void installPlugin(String input) {
        if (getParentActivity() == null) return;

        new Thread(() -> {
            PluginManager manager = PluginManager.getInstance();
            final PluginManager.PluginInfo result;

            if (input.startsWith("http://") || input.startsWith("https://")) {
                result = manager.installPluginFromUrl(input);
            } else {
                String fileName = "plugin_" + System.currentTimeMillis();
                result = manager.installPlugin(input, fileName);
            }

            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (result != null) {
                    Toast.makeText(getParentActivity(), "Плагин установлен: " + result.name, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getParentActivity(), "Ошибка установки плагина", Toast.LENGTH_SHORT).show();
                }
                updateList();
            });
        }).start();
    }

    private void showPluginDetailsDialog(PluginManager.PluginInfo plugin) {
        if (getParentActivity() == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(plugin.name);

        StringBuilder details = new StringBuilder();
        if (plugin.description != null && !plugin.description.isEmpty()) {
            details.append(plugin.description).append("\n\n");
        }
        if (plugin.author != null && !plugin.author.isEmpty()) {
            details.append("Автор: ").append(plugin.author).append("\n");
        }
        if (plugin.version != null && !plugin.version.isEmpty()) {
            details.append("Версия: ").append(plugin.version).append("\n");
        }
        details.append("ID: ").append(plugin.id).append("\n");
        details.append("Файл: ").append(plugin.fileName).append("\n");
        details.append("Статус: ").append(PluginManager.getInstance().isPluginEnabled(plugin.id) ? "Включён" : "Выключен");

        builder.setMessage(details.toString());
        builder.setPositiveButton("OK", null);
        builder.setNegativeButton("Удалить", (dialog, which) -> {
            PluginManager.getInstance().deletePlugin(plugin.id);
            updateList();
            Toast.makeText(getParentActivity(), "Плагин удалён", Toast.LENGTH_SHORT).show();
        });
        builder.setNeutralButton(
            PluginManager.getInstance().isPluginEnabled(plugin.id) ? "Выключить" : "Включить",
            (dialog, which) -> {
                if (PluginManager.getInstance().isPluginEnabled(plugin.id)) {
                    PluginManager.getInstance().disablePlugin(plugin.id);
                } else {
                    PluginManager.getInstance().enablePlugin(plugin.id);
                }
                updateList();
            }
        );
        builder.show();
    }

    private void showClearAllDialog() {
        if (getParentActivity() == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Очистить все плагины?");
        builder.setMessage("Все установленные плагины будут удалены.");
        builder.setPositiveButton("Удалить", (dialog, which) -> {
            ArrayList<PluginManager.PluginInfo> plugins = PluginManager.getInstance().getPlugins();
            for (PluginManager.PluginInfo plugin : new ArrayList<>(plugins)) {
                PluginManager.getInstance().deletePlugin(plugin.id);
            }
            updateList();
            Toast.makeText(getParentActivity(), "Все плагины удалены", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Отмена", null);
        builder.show();
    }
}

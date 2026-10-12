package ru.astra.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.astra.AstraPlugin;
import ru.astra.ability.Ability;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Автодополнение всех подкоманд /astra с учётом прав отправителя. */
public final class TabCompleter implements org.bukkit.command.TabCompleter {

    private static final List<String> ROOT = List.of(
            "menu", "status", "stop", "stopall", "reload", "set", "remove", "cast", "scene");
    private static final List<String> SCENE_ACTIONS = List.of(
            "list", "start", "stop", "edit", "create", "delete", "copy");

    private final AstraPlugin plugin;

    public TabCompleter(AstraPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : ROOT) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT)) && allowed(sender, s)) {
                    out.add(s);
                }
            }
            return out;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "cast" -> {
                if (args.length == 2) {
                    for (Ability a : plugin.abilities().all()) {
                        if (a.id().startsWith(prefix)) {
                            out.add(a.id());
                        }
                    }
                }
            }
            case "scene" -> {
                if (args.length == 2) {
                    for (String s : SCENE_ACTIONS) {
                        if (s.startsWith(prefix)) {
                            out.add(s);
                        }
                    }
                } else if (args.length == 3) {
                    String action = args[1].toLowerCase(Locale.ROOT);
                    if (action.equals("start") || action.equals("edit")
                            || action.equals("delete") || action.equals("copy")) {
                        addSceneIds(out, prefix);
                    }
                } else if (args.length == 4 && args[1].equalsIgnoreCase("copy")) {
                    addSceneIds(out, prefix); // цели для копирования нет — пустой список допустим
                    out.clear();
                }
            }
            case "stop" -> {
                if (args.length == 2) {
                    for (String runId : plugin.sceneManager().active().keySet()) {
                        if (runId.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                            out.add(runId);
                        }
                    }
                }
            }
            case "set", "remove" -> {
                if (args.length == 2) {
                    String pre = args[1].toLowerCase(Locale.ROOT);
                    for (Player p : plugin.getServer().getOnlinePlayers()) {
                        if (p.getName().toLowerCase(Locale.ROOT).startsWith(pre)) {
                            out.add(p.getName());
                        }
                    }
                }
            }
            default -> {
            }
        }
        return out;
    }

    private void addSceneIds(List<String> out, String prefix) {
        for (String id : plugin.sceneManager().saved().keySet()) {
            if (id.startsWith(prefix)) {
                out.add(id);
            }
        }
    }

    private boolean allowed(CommandSender sender, String sub) {
        return switch (sub) {
            case "menu", "cast" -> sender.hasPermission("astra.use");
            case "status" -> sender.hasPermission("astra.scene.view");
            case "stop", "stopall" -> sender.hasPermission("astra.scene.run");
            case "reload" -> sender.hasPermission("astra.reload");
            case "set", "remove" -> sender.hasPermission("astra.assign");
            case "scene" -> sender.hasPermission("astra.scene.view")
                    || sender.hasPermission("astra.scene.edit")
                    || sender.hasPermission("astra.scene.run");
            default -> true;
        };
    }
}

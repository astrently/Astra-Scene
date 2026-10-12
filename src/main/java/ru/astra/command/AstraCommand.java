package ru.astra.command;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.astra.AstraPlugin;
import ru.astra.ability.Ability;
import ru.astra.scene.Scene;
import ru.astra.scene.SceneManager;
import ru.astra.scene.SceneRunner;
import ru.astra.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Единая команда /astra со всеми подкомандами ТЗ:
 * menu, status, stop <id>, stopall, reload, set <игрок>, remove [игрок],
 * cast <способность>, scene list|start|stop|edit ...
 */
public final class AstraCommand implements CommandExecutor {

    private static final String HELP = String.join("\n",
            "/astra menu — главное меню",
            "/astra status — активные сцены",
            "/astra stop <id> — остановить сцену",
            "/astra stopall — остановить всё и очистить ресурсы",
            "/astra reload — перезагрузить конфигурацию",
            "/astra set <игрок> — назначить носителя Astra",
            "/astra remove [игрок] — снять носителя",
            "/astra cast <способность> [режим] — запустить способность",
            "/astra scene list — список сохранённых сцен",
            "/astra scene start <id> — запустить сцену",
            "/astra scene stop <id> — остановить запущенную сцену",
            "/astra scene edit <id> — открыть редактор сцены",
            "/astra scene create <id> [название] — создать сцену",
            "/astra scene delete <id> — удалить сцену",
            "/astra scene copy <id> <новый_id> — копировать сцену");

    private final AstraPlugin plugin;

    public AstraCommand(AstraPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            // /astra без аргументов открывает меню игрокам; консоли — справка.
            if (sender instanceof Player player) {
                return handleMenu(player);
            }
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "menu" -> {
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(Text.error("Меню доступно только игрокам."));
                        return true;
                    }
                    return handleMenu(player);
                }
                case "status" -> {
                    return handleStatus(sender);
                }
                case "stop" -> {
                    return handleStop(sender, args);
                }
                case "stopall" -> {
                    return handleStopAll(sender);
                }
                case "reload" -> {
                    return handleReload(sender);
                }
                case "set" -> {
                    return handleSet(sender, args);
                }
                case "remove" -> {
                    return handleRemove(sender, args);
                }
                case "cast" -> {
                    return handleCast(sender, args);
                }
                case "scene" -> {
                    return handleScene(sender, args);
                }
                default -> {
                    sendHelp(sender);
                }
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Ошибка команды /astra " + sub + ": " + ex.getMessage());
            sender.sendMessage(Text.error("Внутренняя ошибка; подробности в журнале сервера."));
        }
        return true;
    }

    // ==================== Подкоманды ====================

    private boolean handleMenu(Player player) {
        if (!player.hasPermission("astra.use") && !player.hasPermission("astra.admin")) {
            player.sendMessage(Text.error("Недостаточно прав."));
            return true;
        }
        plugin.mainMenu().open(player);
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        if (!sender.hasPermission("astra.scene.view")) {
            sender.sendMessage(Text.error("Нужно право astra.scene.view."));
            return true;
        }
        Map<String, SceneRunner> active = plugin.sceneManager().active();
        if (active.isEmpty()) {
            sender.sendMessage(Text.msg("Активных сцен нет."));
        } else {
            sender.sendMessage(Text.msg("Активные сцены (" + active.size() + "):"));
            for (SceneRunner r : active.values()) {
                sender.sendMessage(Text.info(r.runId(), r.state()
                        + ", шаг " + r.stepIndex() + "/" + r.scene().steps().size()
                        + ", зрители: " + r.viewers().size()));
            }
        }
        sender.sendMessage(Text.msg("Носитель Astra: " + bearerNameOrNone()));
        return true;
    }

    private boolean handleStop(CommandSender sender, String[] args) {
        if (!sender.hasPermission("astra.scene.run")) {
            sender.sendMessage(Text.error("Нужно право astra.scene.run."));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Text.error("Использование: /astra stop <id>"));
            return true;
        }
        String id = args[1];
        SceneManager manager = plugin.sceneManager();
        if (manager.stopRun(id, "остановлено командой " + sender.getName())) {
            sender.sendMessage(Text.ok("Сцена '" + id + "' остановлена и очищена."));
            return true;
        }
        // Допускаем остановку по префиксу runId (scene#N) или по id сохранённой сцены.
        List<String> matched = new ArrayList<>();
        for (String runId : manager.active().keySet()) {
            if (runId.equalsIgnoreCase(id) || runId.startsWith(id + "#")) {
                matched.add(runId);
            }
        }
        if (matched.isEmpty()) {
            sender.sendMessage(Text.error("Активная сцена '" + id + "' не найдена. /astra status"));
            return true;
        }
        for (String runId : matched) {
            manager.stopRun(runId, "остановлено командой " + sender.getName());
            sender.sendMessage(Text.ok("Сцена '" + runId + "' остановлена."));
        }
        return true;
    }

    private boolean handleStopAll(CommandSender sender) {
        if (!sender.hasPermission("astra.scene.run")) {
            sender.sendMessage(Text.error("Нужно право astra.scene.run."));
            return true;
        }
        int scenes = plugin.sceneManager().stopAll("остановлено командой stopall");
        plugin.abilities().stopAll("остановлено командой stopall");
        sender.sendMessage(Text.ok("Остановлено сцен: " + scenes
                + "; способности и временные ресурсы очищены."));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("astra.reload")) {
            sender.sendMessage(Text.error("Нужно право astra.reload."));
            return true;
        }
        plugin.reloadSafely();
        sender.sendMessage(Text.ok("Конфигурация перезагружена; активные сцены не сброшены."));
        return true;
    }

    private boolean handleSet(CommandSender sender, String[] args) {
        if (!sender.hasPermission("astra.assign")) {
            sender.sendMessage(Text.error("Нужно право astra.assign."));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Text.error("Использование: /astra set <игрок>"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Text.error("Игрок '" + args[1] + "' не в сети."));
            return true;
        }
        // Повторное назначение безопасно: старые обработчики/эффекты не дублируются.
        plugin.roleManager().setBearer(target);
        sender.sendMessage(Text.ok("Носителем Astra назначен " + target.getName() + "."));
        return true;
    }

    private boolean handleRemove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("astra.assign")) {
            sender.sendMessage(Text.error("Нужно право astra.assign."));
            return true;
        }
        var current = plugin.roleManager().getBearerUuid();
        if (current == null) {
            sender.sendMessage(Text.msg("Носитель Astra не назначен."));
            return true;
        }
        if (args.length >= 2) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null || !target.getUniqueId().equals(current)) {
                sender.sendMessage(Text.error("Игрок '" + args[1] + "' не является носителем Astra."));
                return true;
            }
        }
        // Снятие не оставляет временных эффектов и активных задач.
        plugin.roleManager().removeBearer();
        sender.sendMessage(Text.ok("Статус носителя Astra снят."));
        return true;
    }

    private boolean handleCast(CommandSender sender, String[] args) {
        if (!sender.hasPermission("astra.use")) {
            sender.sendMessage(Text.error("Нужно право astra.use."));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Text.error("Использование: /astra cast <способность> [режим]. "
                    + "Список: /astra menu"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.error("Запуск способностей из консоли требует игрока-носителя; "
                    + "используйте /astra set, затем команду от лица игрока."));
            return true;
        }
        Ability ability = plugin.abilities().byId(args[1]).orElse(null);
        if (ability == null) {
            sender.sendMessage(Text.error("Неизвестная способность '" + args[1] + "'. "
                    + "Доступны: " + String.join(", ",
                    plugin.abilities().all().stream().map(Ability::id).toList())));
            return true;
        }
        String mode = args.length >= 3 ? args[2] : "";
        Location point = player.getLocation().clone();
        List<Player> viewers = plugin.targetSelector().playersNear(point,
                Math.min(64, plugin.config().maxViewersPerSceneSafe() * 2L),
                plugin.config().maxViewersPerSceneSafe());
        Ability.AbilityContext ctx = new Ability.AbilityContext(player, point, viewers,
                null, mode, String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)));
        Ability.StartCheck check = ability.canStart(ctx);
        if (!check.allowed()) {
            sender.sendMessage(check.reason() == null ? Text.error("Запуск отклонён.") : check.reason());
            return true;
        }
        ability.start(ctx);
        sender.sendMessage(Text.ok("Способность «" + ability.displayName() + "» запущена."));
        return true;
    }

    private boolean handleScene(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Text.error("Использование: /astra scene "
                    + "<list|start|stop|edit|create|delete|copy> ..."));
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        SceneManager manager = plugin.sceneManager();

        switch (action) {
            case "list" -> {
                if (!sender.hasPermission("astra.scene.view")) {
                    sender.sendMessage(Text.error("Нужно право astra.scene.view."));
                    return true;
                }
                if (manager.saved().isEmpty()) {
                    sender.sendMessage(Text.msg("Сохранённых сцен нет."));
                    return true;
                }
                sender.sendMessage(Text.msg("Сохранённые сцены:"));
                for (Scene s : manager.saved().values()) {
                    sender.sendMessage(Text.info(s.id(), s.title()
                            + " (шагов: " + s.steps().size() + ")"));
                }
            }
            case "start" -> {
                if (!sender.hasPermission("astra.scene.run")) {
                    sender.sendMessage(Text.error("Нужно право astra.scene.run."));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(Text.error("Использование: /astra scene start <id>"));
                    return true;
                }
                Scene scene = manager.get(args[2]);
                if (scene == null) {
                    sender.sendMessage(Text.error("Сцена '" + args[2] + "' не найдена."));
                    return true;
                }
                Player caster = sender instanceof Player p ? p : plugin.roleManager().getBearerPlayer();
                if (caster == null) {
                    sender.sendMessage(Text.error("Нужен игрок-источник (запустите от игрока"
                            + " или назначьте носителя через /astra set)."));
                    return true;
                }
                List<Player> viewers = plugin.targetSelector().playersNear(caster.getLocation(), 64,
                        plugin.config().maxViewersPerSceneSafe());
                String runId = manager.start(scene, caster, viewers, feedbackOf(sender));
                if (runId != null) {
                    sender.sendMessage(Text.ok("Сцена «" + scene.title() + "» запущена: " + runId));
                }
            }
            case "stop" -> {
                if (args.length < 3) {
                    sender.sendMessage(Text.error("Использование: /astra scene stop <id>"));
                    return true;
                }
                return handleStop(sender, new String[]{"stop", args[2]});
            }
            case "edit" -> {
                if (!sender.hasPermission("astra.scene.edit")) {
                    sender.sendMessage(Text.error("Нужно право astra.scene.edit."));
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Text.error("Редактор доступен только игрокам."));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(Text.error("Использование: /astra scene edit <id>"));
                    return true;
                }
                Scene scene = manager.get(args[2]);
                if (scene == null) {
                    sender.sendMessage(Text.error("Сцена '" + args[2] + "' не найдена."));
                    return true;
                }
                plugin.sceneEditor().open(player, scene.id());
            }
            case "create" -> {
                if (!sender.hasPermission("astra.scene.edit")) {
                    sender.sendMessage(Text.error("Нужно право astra.scene.edit."));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(Text.error("Использование: /astra scene create <id> [название]"));
                    return true;
                }
                String id = args[2].toLowerCase(Locale.ROOT);
                if (!SceneManager.validId(id)) {
                    sender.sendMessage(Text.error("Недопустимый идентификатор: 3-32 символа, "
                            + "буквы/цифры/подчёркивание."));
                    return true;
                }
                if (manager.exists(id)) {
                    sender.sendMessage(Text.error("Сцена '" + id + "' уже существует."));
                    return true;
                }
                String title = args.length >= 4
                        ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length))
                        : id;
                manager.create(id, title, "");
                manager.saveAll();
                sender.sendMessage(Text.ok("Создана сцена '" + id + "'. Редактор: /astra scene edit " + id));
            }
            case "delete" -> {
                if (!sender.hasPermission("astra.scene.edit")) {
                    sender.sendMessage(Text.error("Нужно право astra.scene.edit."));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(Text.error("Использование: /astra scene delete <id>"));
                    return true;
                }
                if (manager.delete(args[2])) {
                    manager.saveAll();
                    sender.sendMessage(Text.ok("Сцена '" + args[2] + "' удалена."));
                } else {
                    sender.sendMessage(Text.error("Сцена '" + args[2] + "' не найдена."));
                }
            }
            case "copy" -> {
                if (!sender.hasPermission("astra.scene.edit")) {
                    sender.sendMessage(Text.error("Нужно право astra.scene.edit."));
                    return true;
                }
                if (args.length < 4) {
                    sender.sendMessage(Text.error("Использование: /astra scene copy <id> <новый_id>"));
                    return true;
                }
                Scene source = manager.get(args[2]);
                String newId = args[3].toLowerCase(Locale.ROOT);
                if (source == null) {
                    sender.sendMessage(Text.error("Исходная сцена '" + args[2] + "' не найдена."));
                    return true;
                }
                if (!SceneManager.validId(newId) || manager.exists(newId)) {
                    sender.sendMessage(Text.error("Недопустимый или занятый новый идентификатор."));
                    return true;
                }
                manager.saved().put(newId, source.copyAs(newId, source.title() + " (копия)"));
                manager.saveAll();
                sender.sendMessage(Text.ok("Сцена скопирована в '" + newId + "'."));
            }
            default -> sender.sendMessage(Text.error("Неизвестное действие scene: " + action));
        }
        return true;
    }

    private SceneManager.CommandFeedback feedbackOf(CommandSender sender) {
        if (sender instanceof Player player) {
            return SceneManager.CommandFeedback.to(player);
        }
        return SceneManager.CommandFeedback.console();
    }

    private String bearerNameOrNone() {
        Player bearer = plugin.roleManager().getBearerPlayer();
        if (bearer != null) {
            return bearer.getName();
        }
        var uuid = plugin.roleManager().getBearerUuid();
        return uuid == null ? "не назначен" : "назначен (не в сети): " + uuid;
    }

    private void sendHelp(CommandSender sender) {
        for (String line : HELP.split("\n")) {
            sender.sendMessage(Text.msg(line));
        }
    }
}

package com.gamepaper.tictactoe;

import org.bukkit.command.PluginCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class GamePaperPlugin extends JavaPlugin {

    private TicTacToeManager gameManager;
    private GomokuManager gomokuManager;
    private RandomGameManager randomGameManager;
    private TntGameManager tntGameManager;
    private UndercoverGameManager undercoverGameManager;
    private PvpGameManager pvpGameManager;
    private GameGui gameGui;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        int inviteTimeoutSeconds = Math.max(10, getConfig().getInt("invite-timeout-seconds", 60));
        int endDisplaySeconds = Math.max(1, getConfig().getInt("end-display-seconds", 3));
        this.gameManager = new TicTacToeManager(this, inviteTimeoutSeconds, endDisplaySeconds);
        this.gomokuManager = new GomokuManager(this, gameManager, inviteTimeoutSeconds, endDisplaySeconds);
        this.randomGameManager = new RandomGameManager(this, gomokuManager);
        this.tntGameManager = new TntGameManager(this, gomokuManager, randomGameManager);
        this.undercoverGameManager = new UndercoverGameManager(
                this, gomokuManager, randomGameManager, tntGameManager);
        this.pvpGameManager = new PvpGameManager(
                this, gomokuManager, randomGameManager, tntGameManager, undercoverGameManager);
        this.randomGameManager.setTntGameManager(tntGameManager);
        this.randomGameManager.setUndercoverGameManager(undercoverGameManager);
        this.randomGameManager.setPvpGameManager(pvpGameManager);
        this.tntGameManager.setUndercoverGameManager(undercoverGameManager);
        this.tntGameManager.setPvpGameManager(pvpGameManager);
        this.undercoverGameManager.setPvpGameManager(pvpGameManager);
        this.gameGui = new GameGui(
                this, randomGameManager, tntGameManager, undercoverGameManager, pvpGameManager);

        TicTacToeCommand command = new TicTacToeCommand(gameManager, gomokuManager);
        PluginCommand tttCommand = Objects.requireNonNull(getCommand("ttt"), "Command 'ttt' is missing from plugin.yml");
        tttCommand.setExecutor(command);
        tttCommand.setTabCompleter(command);

        GomokuCommand gomokuCommand = new GomokuCommand(gomokuManager);
        PluginCommand gomokuPluginCommand = Objects.requireNonNull(
                getCommand("gomoku"), "Command 'gomoku' is missing from plugin.yml");
        gomokuPluginCommand.setExecutor(gomokuCommand);
        gomokuPluginCommand.setTabCompleter(gomokuCommand);

        RandomGameCommand randomGameCommand = new RandomGameCommand(randomGameManager);
        TntGameCommand tntGameCommand = new TntGameCommand(tntGameManager);
        UndercoverGameCommand undercoverGameCommand =
                new UndercoverGameCommand(undercoverGameManager);
        PvpGameCommand pvpGameCommand = new PvpGameCommand(pvpGameManager);
        GamePaperCommand gameCommand = new GamePaperCommand(
                this, command, gomokuCommand, randomGameCommand, tntGameCommand,
                undercoverGameCommand, pvpGameCommand, gameGui);
        PluginCommand gamePluginCommand = Objects.requireNonNull(
                getCommand("game"), "Command 'game' is missing from plugin.yml");
        gamePluginCommand.setExecutor(gameCommand);
        gamePluginCommand.setTabCompleter(gameCommand);

        getServer().getPluginManager().registerEvents(new TicTacToeListener(gameManager), this);
        getServer().getPluginManager().registerEvents(new GomokuListener(gomokuManager), this);
        getServer().getPluginManager().registerEvents(new RandomGameListener(randomGameManager), this);
        getServer().getPluginManager().registerEvents(new TntGameListener(tntGameManager), this);
        getServer().getPluginManager().registerEvents(
                new UndercoverGameListener(undercoverGameManager, gameGui), this);
        getServer().getPluginManager().registerEvents(new PvpGameListener(pvpGameManager), this);
        getServer().getPluginManager().registerEvents(gameGui, this);
        getLogger().info("GamePaper 已启用。使用 /game help 查看命令。");
    }

    @Override
    public void onDisable() {
        if (gameGui != null) {
            gameGui.shutdown();
        }
        if (pvpGameManager != null) {
            pvpGameManager.shutdown();
        }
        if (undercoverGameManager != null) {
            undercoverGameManager.shutdown();
        }
        if (tntGameManager != null) {
            tntGameManager.shutdown();
        }
        if (randomGameManager != null) {
            randomGameManager.shutdown();
        }
        if (gomokuManager != null) {
            gomokuManager.shutdown();
        }
        if (gameManager != null) {
            gameManager.shutdown();
        }
    }

    void reloadGameConfig(CommandSender sender) {
        if (!sender.hasPermission("gamepaper.reload")) {
            Messages.send(sender, "你没有权限重载 GamePaper 配置。" );
            return;
        }
        if ((randomGameManager != null && !randomGameManager.canReloadConfig())
                || (tntGameManager != null && !tntGameManager.canReloadConfig())
                || (undercoverGameManager != null && !undercoverGameManager.canReloadConfig())
                || (pvpGameManager != null && !pvpGameManager.canReloadConfig())) {
            Messages.send(sender, "多人小游戏正在进行中，结束对局后才能重载配置。" );
            return;
        }

        reloadConfig();
        if (gameManager != null) {
            gameManager.reloadConfig();
        }
        if (gomokuManager != null) {
            gomokuManager.reloadConfig();
        }
        if (randomGameManager != null) {
            randomGameManager.reloadConfig();
        }
        if (tntGameManager != null) {
            tntGameManager.reloadConfig();
        }
        if (undercoverGameManager != null) {
            undercoverGameManager.reloadConfig();
        }
        if (pvpGameManager != null) {
            pvpGameManager.reloadConfig();
        }
        Messages.send(sender, "GamePaper 配置已重载，新的游戏设置将在下一局生效。" );
    }
}

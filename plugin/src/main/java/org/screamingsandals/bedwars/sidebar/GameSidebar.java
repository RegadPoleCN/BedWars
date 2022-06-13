/*
 * Copyright (C) 2022 ScreamingSandals
 *
 * This file is part of Screaming BedWars.
 *
 * Screaming BedWars is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Screaming BedWars is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Screaming BedWars. If not, see <https://www.gnu.org/licenses/>.
 */

package org.screamingsandals.bedwars.sidebar;

import org.screamingsandals.bedwars.VersionInfo;
import org.screamingsandals.bedwars.api.config.ConfigurationContainer;
import org.screamingsandals.bedwars.api.game.GameStatus;
import org.screamingsandals.bedwars.game.GameImpl;
import org.screamingsandals.bedwars.game.TeamImpl;
import org.screamingsandals.bedwars.lang.LangKeys;
import org.screamingsandals.bedwars.listener.Player116ListenerUtils;
import org.screamingsandals.bedwars.player.BedWarsPlayer;
import org.screamingsandals.bedwars.player.PlayerManagerImpl;
import org.screamingsandals.lib.lang.Message;
import org.screamingsandals.lib.packet.SClientboundSetPlayerTeamPacket;
import org.screamingsandals.lib.player.PlayerWrapper;
import org.screamingsandals.lib.sidebar.ScoreSidebar;
import org.screamingsandals.lib.sidebar.Sidebar;
import org.screamingsandals.lib.sidebar.TeamedSidebar;
import org.screamingsandals.lib.spectator.Color;
import org.screamingsandals.lib.spectator.Component;
import org.screamingsandals.lib.tasker.Tasker;
import org.screamingsandals.lib.tasker.TaskerTime;
import org.screamingsandals.lib.tasker.task.TaskerTask;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class GameSidebar {

    private GameStatus status = GameStatus.WAITING;
    private final GameImpl game;
    private Sidebar sidebar = Sidebar.of();
    private ScoreSidebar scoreboard;
    private TeamedSidebar<?> teamedSidebar = sidebar;
    private final TaskerTask task;

    public GameSidebar(GameImpl game) {
        this.game = game;
        if (game.getConfigurationContainer().getOrDefault(ConfigurationContainer.SIDEBAR_LOBBY_ENABLED, false)) {
            this.sidebar
                    .title(Component.fromMiniMessage(game.getConfigurationContainer().getOrDefault(ConfigurationContainer.SIDEBAR_LOBBY_TITLE, "<yellow>BEDWARS")));
            game.getConfigurationContainer().getOrDefault(ConfigurationContainer.SIDEBAR_LOBBY_CONTENT, List.of())
                    .stream()
                    .filter(Objects::nonNull)
                    .map(message -> Message.ofRichText(message)
                            .placeholder("game", game.getDisplayNameComponent())
                            .placeholder("players", () -> Component.text(game.countConnectedPlayers()))
                            .placeholder("max-players", game.getMaxPlayers())
                            .placeholder("time", () -> Component.text(game.getFormattedTimeLeft()))
                            .placeholder("version", VersionInfo.VERSION)
                            .placeholder("date", getFormattedDate(game.getConfigurationContainer().getOrDefault(ConfigurationContainer.SIDEBAR_DATE_FORMAT, "date-format")))
                            .placeholder("mode", checkMode())
                            .placeholder("state", sender -> {
                                if (game.countConnectedPlayers() >= game.getMinPlayers() && (game.countActiveTeams() > 1
                                        || game.getConfigurationContainer().getOrDefault(ConfigurationContainer.JOIN_RANDOM_TEAM_AFTER_LOBBY, false))) {
                                    var seconds = game.getTimeLeft();
                                    return Message.of(LangKeys.IN_GAME_SCOREBOARD_STATE_COUNTDOWN)
                                            .placeholder("countdown", seconds)
                                            .asComponent(sender);
                                } else {
                                    return Message.of(LangKeys.IN_GAME_SCOREBOARD_STATE_WAITING)
                                            .asComponent(sender);
                                }
                            })
                    )
                    .forEach(sidebar::bottomLine);
            sidebar.show();
        }
        game.getConnectedPlayers().forEach(sidebar::addViewer);

        this.task = Tasker
                .build(this::update)
                .async()
                .repeat(20, TaskerTime.TICKS)
                .start();
    }

    private void switchToRunning() {
        sidebar.setLines(List.of());
        sidebar.title(
                Message.ofRichText(game.getConfigurationContainer().getOrDefault(ConfigurationContainer.SIDEBAR_GAME_TITLE, "<yellow>BEDWARS"))
                        .placeholder("game", game.getDisplayNameComponent())
                        .placeholder("time", () -> Component.text(game.getFormattedTimeLeft()))
        );

        game.getConfigurationContainer()
                .getOrDefault(ConfigurationContainer.SIDEBAR_GAME_CONTENT, List.of())
                .forEach(line -> {
                    if (line == null) {
                        return; // why
                    }
                    if (line.trim().equalsIgnoreCase("<team-status>")) {
                        game.getActiveTeams().stream().map(this::formatScoreboardTeam).forEach(sidebar::bottomLine);
                        return;
                    }
                    sidebar.bottomLine(
                            Message.ofRichText(line)
                                    .placeholder("game", game.getDisplayNameComponent())
                                    .placeholder("players", () -> Component.text(game.countConnectedPlayers()))
                                    .placeholder("max-players", game.getMaxPlayers())
                                    .placeholder("time", () -> Component.text(game.getFormattedTimeLeft()))
                                    .placeholder("version", VersionInfo.VERSION)
                                    .placeholder("date", getFormattedDate(game.getConfigurationContainer().getOrDefault(ConfigurationContainer.SIDEBAR_DATE_FORMAT, "date-format")))
                                    .placeholder("mode", checkMode())
                                    .placeholder("tier", () -> Component.text("Not implemented yet.", Color.RED)) // TODO
                                    .placeholder("kills", () -> Component.text("Not implemented yet.", Color.RED)) // TODO
                                    .placeholder("target-blocks-destroyed", () -> Component.text("Not implemented yet.", Color.RED)) // TODO
                    );
                });
    }

    private void switchToRunningOld() {
        // Switch the sidebar
        var scoreboard = ScoreSidebar.of();
        var viewers = sidebar.viewers();
        // teams will be restored by update task
        sidebar.destroy();
        viewers.forEach(scoreboard::addViewer);
        sidebar = null;
        this.teamedSidebar = this.scoreboard = scoreboard;
        scoreboard.title(
                Message.ofRichText(game.getConfigurationContainer().getOrDefault(ConfigurationContainer.SIDEBAR_GAME_TITLE, "<yellow>BEDWARS"))
                        .placeholder("game", game.getDisplayNameComponent())
                        .placeholder("time", () -> Component.text(game.getFormattedTimeLeft()))
        );

        updateScoreboard();

        scoreboard.show();
    }

    private void update() {
        if (scoreboard != null) {
            updateScoreboard();
        }

        if (teamedSidebar != null) {
            teamedSidebar.update();
        }

        if (game.getStatus() == GameStatus.RUNNING && status != GameStatus.RUNNING) {
            if (game.getConfigurationContainer().getOrDefault(ConfigurationContainer.SIDEBAR_GAME_ENABLED, false)) {
                if (!game.getConfigurationContainer().getOrDefault(ConfigurationContainer.SIDEBAR_GAME_LEGACY_SIDEBAR, false)) {
                    switchToRunning();
                } else {
                    switchToRunningOld();
                }
            } else {
                teamedSidebar.hide();
            }
            status = GameStatus.RUNNING;
        }

        game.getActiveTeams().forEach(team -> {
            if (teamedSidebar.getTeam(team.getName()).isEmpty()) {
                teamedSidebar.team(team.getName())
                        .color(SClientboundSetPlayerTeamPacket.TeamColor.valueOf(Color.nearestNamedTo(team.getColor().getTextColor()).toString().toUpperCase())) // TODO: a better way
                        .friendlyFire(game.getConfigurationContainer().getOrDefault(ConfigurationContainer.FRIENDLYFIRE, false));
            }
            var sidebarTeam = teamedSidebar.getTeam(team.getName()).orElseThrow();

            List.copyOf(sidebarTeam.players())
                    .forEach(teamPlayer -> {
                        if (team.getPlayers().stream().noneMatch(bedWarsPlayer -> bedWarsPlayer.equals(teamPlayer))) {
                            sidebarTeam.removePlayer(teamPlayer);
                        }
                    });

            team.getPlayers()
                    .stream()
                    .filter(player -> !sidebarTeam.players().contains(player))
                    .forEach(sidebarTeam::player);
        });
    }

    private Message formatScoreboardTeam(TeamImpl team) {
        if (team == null) {
            return null;
        }

        return Message.ofRichText(game.getConfigurationContainer().getOrDefault(ConfigurationContainer.SIDEBAR_GAME_TEAM_LINE, "<target-block-prefix><team-color><team>"))
                .earlyPlaceholder("team-color", "<color:" + team.getColor().getTextColor().toString() + ">") // TODO: replace this sin
                .placeholder("team-size", () -> Component.text(team.countConnectedPlayers()))
                .placeholder("team", team.getName())
                .placeholder("target-block-prefix", sender -> {
                    if (team.isTargetBlockIntact()) {
                        if (team.getTargetBlock().getBlock().getType().isSameType("respawn_anchor") && Player116ListenerUtils.isAnchorEmpty(team.getTargetBlock().getBlock())) {
                            return Message.ofRichText(game.getConfigurationContainer().getOrDefault(ConfigurationContainer.SIDEBAR_GAME_TEAM_PREFIXES_ANCHOR_EMPTY, "")).asComponent(sender);
                        } else {
                            return Message.ofRichText(game.getConfigurationContainer().getOrDefault(ConfigurationContainer.SIDEBAR_GAME_TEAM_PREFIXES_TARGET_BLOCK_EXISTS, "")).asComponent(sender);
                        }
                    } else {
                        return Message.ofRichText(game.getConfigurationContainer().getOrDefault(ConfigurationContainer.SIDEBAR_GAME_TEAM_PREFIXES_TARGET_BLOCK_LOST, "")).asComponent(sender);
                    }
                })
                .placeholder("you", sender -> {
                    if (sender instanceof PlayerWrapper) { // legacy sidebar means sender is null
                        var player = sender.as(PlayerWrapper.class);
                        if (PlayerManagerImpl.getInstance().isPlayerInGame(player)) {
                            var bwPlayer = player.as(BedWarsPlayer.class);
                            if (team.isPlayerInTeam(bwPlayer)) {
                                return Component.text().append("").append(Message.of(LangKeys.IN_GAME_SCOREBOARD_YOU).asComponent(sender)).build();
                            }
                        }
                    }
                    return Component.empty();
                });
    }

    private void updateScoreboard() {
        game.getActiveTeams().forEach(team -> {
            scoreboard.entity(team.getName(), formatScoreboardTeam(team).asComponent());
            scoreboard.score(team.getName(), team.countConnectedPlayers());
        });
    }

    public void destroy() {
        task.cancel();
        teamedSidebar.destroy();
    }

    public void addPlayer(PlayerWrapper player) {
        teamedSidebar.addViewer(player);
    }

    public void removePlayer(PlayerWrapper player) {
        teamedSidebar.removeViewer(player);
    }

    public String getFormattedDate(String format) {
        try {
            return new SimpleDateFormat(format).format(new Date());
        } catch (Throwable ignored) {
            return DateTimeFormatter.ISO_DATE.format(LocalDate.now());
        }
    }

    public Message checkMode() {
        if (game.getAvailableTeams().stream().allMatch(t -> t.getMaxPlayers() == 1)) {
            return Message.of(LangKeys.IN_GAME_SCOREBOARD_MODE_SOLO);
        } else if (game.getAvailableTeams().stream().allMatch(t -> t.getMaxPlayers() == 2)) {
            return Message.of(LangKeys.IN_GAME_SCOREBOARD_MODE_DOUBLES);
        } else if (game.getAvailableTeams().stream().allMatch(t -> t.getMaxPlayers() == 3)) {
            return Message.of(LangKeys.IN_GAME_SCOREBOARD_MODE_TRIPLES);
        } else if (game.getAvailableTeams().stream().allMatch(t -> t.getMaxPlayers() == 4)) {
            return Message.of(LangKeys.IN_GAME_SCOREBOARD_MODE_SQUADS);
        } else {
            var teamSize = game.getAvailableTeams().stream()
                    .map(TeamImpl::getMaxPlayers)
                    .map(String::valueOf)
                    .collect(Collectors.toList());
            return Message.ofPlainText(String.join("v", teamSize));
        }
    }
}

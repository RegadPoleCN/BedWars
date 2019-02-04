package misat11.bw.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import misat11.bw.api.Game;
import misat11.bw.api.Team;

public class BedwarsPlayerJoinedEvent extends Event {

	private static final HandlerList handlers = new HandlerList();
	private Game game = null;
	private Player player = null;
	private Team team = null;

	public BedwarsPlayerJoinedEvent(Game game, Team team, Player player) {
		this.game = game;
		this.player = player;
		this.team = team;
	}

	public static HandlerList getHandlerList() {
		return BedwarsPlayerJoinedEvent.handlers;
	}

	public Game getGame() {
		return this.game;
	}

	@Override
	public HandlerList getHandlers() {
		return BedwarsPlayerJoinedEvent.handlers;
	}

	public Player getPlayer() {
		return this.player;
	}

	public Team getTeam() {
		return this.team;
	}

}

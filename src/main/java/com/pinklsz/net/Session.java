package com.pinklsz.net;

import javax.inject.Singleton;

/**
 * Whether the player for this login has loaded. The game replays a lot of varps while it logs you in, and those must not be
 * mistaken for kills or new collection log items, so collectors wait until the player (and their name) exists, the same way
 * the Valiance plugin does: the plugin calls {@link #loaded()} once the local player's name is populated.
 */
@Singleton
public class Session
{
	private volatile boolean loaded;

	public void loaded()
	{
		loaded = true;
	}

	public void loggedOut()
	{
		loaded = false;
	}

	public boolean settled()
	{
		return loaded;
	}
}

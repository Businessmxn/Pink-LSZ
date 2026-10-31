package com.pinklsz.net;

/** What an upload is. {@code wire} is the name the dashboard knows it by, {@code label} is how chat messages describe it. */
public enum Kind
{
	HELLO("hello", null),
	DROP("loot", "a drop"),
	DROP_TOTALS("loot_total", "your saved drop totals"),
	KILL_COUNTS("kc", "kill counts"),
	COLLECTION_LOG("clog", "your collection log"),
	COLLECTION_LOG_ITEM("clog_new", "a new collection log item"),
	COMBAT_ACHIEVEMENTS("ca", "your combat achievements"),
	PERSONAL_BEST("pb", "a personal best"),
	DEATH("death", "a death");

	final String wire;
	final String label;

	Kind(String wire, String label)
	{
		this.wire = wire;
		this.label = label;
	}
}

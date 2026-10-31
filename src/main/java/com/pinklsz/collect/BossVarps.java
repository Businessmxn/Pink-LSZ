package com.pinklsz.collect;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarPlayerID;

/** Kill count varps, named like Wise Old Man's boss metrics. */
final class BossVarps
{
	static final String DOOM = "doom_of_mokhaiotl";

	private static final Map<Integer, String> MAP = new HashMap<>();

	private static void add(int varp, String boss)
	{
		MAP.put(varp, boss);
	}

	static
	{
		add(VarPlayerID.TOTAL_ABYSSALSIRE_KILLS, "abyssal_sire");
		add(VarPlayerID.TOTAL_HYDRABOSS_KILLS, "alchemical_hydra");
		add(VarPlayerID.TOTAL_AMOXLIATL_KILLS, "amoxliatl");
		add(VarPlayerID.TOTAL_ARAXXOR_KILLS, "araxxor");
		add(VarPlayerID.TOTAL_ARTIO_KILLS, "artio");
		add(VarPlayerID.TOTAL_BARROWS_CHESTS, "barrows_chests");
		add(VarPlayerID.TOTAL_COWBOSS_KILLS, "brutus");
		add(VarPlayerID.TOTAL_COWBOSS_HARDMODE_KILLS, "brutus_hard_mode");
		add(VarPlayerID.TOTAL_BRYOPHYTA_KILLS, "bryophyta");
		add(VarPlayerID.TOTAL_CALLISTO_KILLS, "callisto");
		add(VarPlayerID.TOTAL_CALVARION_KILLS, "calvarion");
		add(VarPlayerID.TOTAL_CERBERUS_KILLS, "cerberus");
		add(VarPlayerID.TOTAL_COMPLETED_XERICCHAMBERS, "chambers_of_xeric");
		add(VarPlayerID.TOTAL_COMPLETED_XERICCHAMBERS_CHALLENGE, "chambers_of_xeric_challenge_mode");
		add(VarPlayerID.TOTAL_CHAOSELE_KILLS, "chaos_elemental");
		add(VarPlayerID.TOTAL_CHAOSFANATIC_KILLS, "chaos_fanatic");
		add(VarPlayerID.TOTAL_SARADOMIN_KILLS, "commander_zilyana");
		add(VarPlayerID.TOTAL_CORP_KILLS, "corporeal_beast");
		add(VarPlayerID.TOTAL_CRAZYARCHAEOLOGIST_KILLS, "crazy_archaeologist");
		add(VarPlayerID.TOTAL_PRIME_KILLS, "dagannoth_prime");
		add(VarPlayerID.TOTAL_REX_KILLS, "dagannoth_rex");
		add(VarPlayerID.TOTAL_SUPREME_KILLS, "dagannoth_supreme");
		add(VarPlayerID.TOTAL_DERANGEDARCHAEOLOGIST_KILLS, "deranged_archaeologist");
		add(VarPlayerID.TOTAL_DUKE_SUCELLUS_KILLS, "duke_sucellus");
		add(VarPlayerID.TOTAL_DUKE_SUCELLUS_AWAKENED_KILLS, "duke_sucellus_awakened");
		add(VarPlayerID.TOTAL_BANDOS_KILLS, "general_graardor");
		add(VarPlayerID.TOTAL_MOLE_KILLS, "giant_mole");
		add(VarPlayerID.TOTAL_GARGBOSS_KILLS, "grotesque_guardians");
		add(VarPlayerID.TOTAL_HESPORI_KILLS, "hespori");
		add(VarPlayerID.TOTAL_KALPHITE_KILLS, "kalphite_queen");
		add(VarPlayerID.TOTAL_KBD_KILLS, "king_black_dragon");
		add(VarPlayerID.TOTAL_KRAKEN_BOSS_KILLS, "kraken");
		add(VarPlayerID.TOTAL_ARMADYL_KILLS, "kreearra");
		add(VarPlayerID.TOTAL_ZAMORAK_KILLS, "kril_tsutsaroth");
		add(VarPlayerID.TOTAL_PMOON_CHESTS, "lunar_chests");
		add(VarPlayerID.TOTAL_MAGGOT_KING_KILLS, "maggot_king");
		add(VarPlayerID.TOTAL_MIMIC_KILLS, "mimic");
		add(VarPlayerID.TOTAL_NEX_KILLS, "nex");
		add(VarPlayerID.TOTAL_NIGHTMARE_KILLS, "nightmare");
		add(VarPlayerID.TOTAL_NIGHTMARE_CHALLENGE_KILLS, "phosanis_nightmare");
		add(VarPlayerID.TOTAL_HILLGIANT_BOSS_KILLS, "obor");
		add(VarPlayerID.TOTAL_MUSPAH_KILLS, "phantom_muspah");
		add(VarPlayerID.TOTAL_SARACHNIS_KILLS, "sarachnis");
		add(VarPlayerID.TOTAL_SCORPIA_KILLS, "scorpia");
		add(VarPlayerID.TOTAL_RAT_BOSS_KILLS, "scurrius");
		add(VarPlayerID.TOTAL_GRYPHON_BOSS_KILLS, "shellbane_gryphon");
		add(VarPlayerID.TOTAL_CATA_BOSS_KILLS, "skotizo");
		add(VarPlayerID.TOTAL_SOL_KILLS, "sol_heredit");
		add(VarPlayerID.TOTAL_SPINDEL_KILLS, "spindel");
		add(VarPlayerID.TOTAL_TEMPOROSS_KILLS, "tempoross");
		add(VarPlayerID.TOTAL_COMPLETED_GAUNTLET, "the_gauntlet");
		add(VarPlayerID.TOTAL_COMPLETED_GAUNTLET_HM, "the_corrupted_gauntlet");
		add(VarPlayerID.TOTAL_HUEY_KILLS, "the_hueycoatl");
		add(VarPlayerID.TOTAL_LEVIATHAN_KILLS, "the_leviathan");
		add(VarPlayerID.TOTAL_LEVIATHAN_AWAKENED_KILLS, "the_leviathan_awakened");
		add(VarPlayerID.TOTAL_ROYAL_TITAN_KILLS, "the_royal_titans");
		add(VarPlayerID.TOTAL_WHISPERER_KILLS, "the_whisperer");
		add(VarPlayerID.TOTAL_WHISPERER_AWAKENED_KILLS, "the_whisperer_awakened");
		add(VarPlayerID.TOTAL_COMPLETED_THEATREOFBLOOD, "theatre_of_blood");
		add(VarPlayerID.TOTAL_COMPLETED_THEATREOFBLOOD_HARD, "theatre_of_blood_hard_mode");
		add(VarPlayerID.TOTAL_THERMY_KILLS, "thermonuclear_smoke_devil");
		add(VarPlayerID.TOTAL_COMPLETED_TOMBSOFAMASCUT, "tombs_of_amascut");
		add(VarPlayerID.TOTAL_COMPLETED_TOMBSOFAMASCUT_EXPERT, "tombs_of_amascut_expert");
		add(VarPlayerID.TOTAL_ZUK_KILLS, "tzkal_zuk");
		add(VarPlayerID.TOTAL_JAD_KILLS, "tztok_jad");
		add(VarPlayerID.TOTAL_VARDORVIS_KILLS, "vardorvis");
		add(VarPlayerID.TOTAL_VARDORVIS_AWAKENED_KILLS, "vardorvis_awakened");
		add(VarPlayerID.TOTAL_VENENATIS_KILLS, "venenatis");
		add(VarPlayerID.TOTAL_VETION_KILLS, "vetion");
		add(VarPlayerID.TOTAL_VORKATH_KILLS, "vorkath");
		add(VarPlayerID.TOTAL_WINTERTODT_KILLS, "wintertodt");
		add(VarPlayerID.TOTAL_YAMA_KILLS, "yama");
		add(VarPlayerID.TOTAL_ZALCANO_KILLS, "zalcano");
		add(VarPlayerID.TOTAL_SNAKEBOSS_KILLS, "zulrah");
		add(VarPlayerID.DOM_LEVEL_8_COMPLETIONS, DOOM);
		add(VarPlayerID.DOM_LEVEL_8_PLUS_COMPLETIONS, DOOM);
		add(5712, "mad_angel");
	}

	private BossVarps()
	{
	}

	static String bossFor(int varp)
	{
		return MAP.get(varp);
	}

	static Iterable<Map.Entry<Integer, String>> all()
	{
		return MAP.entrySet();
	}

	/** Doom of Mokhaiotl is wave 8 plus wave 8+. */
	static int read(Client client, String boss)
	{
		if (DOOM.equals(boss))
		{
			return client.getVarpValue(VarPlayerID.DOM_LEVEL_8_COMPLETIONS) + client.getVarpValue(VarPlayerID.DOM_LEVEL_8_PLUS_COMPLETIONS);
		}
		for (Map.Entry<Integer, String> e : MAP.entrySet())
		{
			if (e.getValue().equals(boss))
			{
				return client.getVarpValue(e.getKey());
			}
		}
		return 0;
	}
}

package com.smccloud.hotdeath


/**
 * This class wraps all the prefs checking; it's a bit of a vestige of this application's
 * PocketPC origins, but it also makes it easier to call Prefs.get*() functions, since it
 * always has handy access to the GameActivity, and thus, the context.
 * @author priebe
 *
 */
class GameOptions(private var m_ga: GameActivity?)
{
	fun shutdown ()
	{
		m_ga = null
	}

	fun getP1Skill(): Int
	{
		return Prefs.getP1SkillLevel(m_ga)
	}

	fun getP2Skill(): Int
	{
		return Prefs.getP2SkillLevel(m_ga)
	}

	fun getP3Skill(): Int
	{
		return Prefs.getP3SkillLevel(m_ga)
	}

	fun getP1Agg(): Int
	{
		return Prefs.getP1AggressionLevel(m_ga)
	}

	fun getP2Agg(): Int
	{
		return Prefs.getP2AggressionLevel(m_ga)
	}

	fun getP3Agg(): Int
	{
		return Prefs.getP3AggressionLevel(m_ga)
	}

	fun getPauseLength(): Int
	{
		return Prefs.getGameSpeed(m_ga)
	}

	fun getCheatLevel(): Int
	{
		return Prefs.getCheatLevel(m_ga)
	}

	fun getFamilyFriendly (): Boolean
	{
		val s = Prefs.getCheatCode (m_ga)
		if (s.contains("originalhotdeath"))
		{
			return false
		}

		return true
	}

	fun getFaceUp(): Boolean
	{
		return Prefs.getFaceUp(m_ga)
	}

	fun getComputer4th(): Boolean
	{
		return Prefs.getComputer4th(m_ga)
	}

	fun getStandardRules (): Boolean
	{
		val s = Prefs.getCheatCode (m_ga)
		if (s.contains("standardrules"))
		{
			return true
		}

		return false
	}

	fun getOneDeck(): Boolean
	{
		return !Prefs.getTwoDecks(m_ga)
	}
}
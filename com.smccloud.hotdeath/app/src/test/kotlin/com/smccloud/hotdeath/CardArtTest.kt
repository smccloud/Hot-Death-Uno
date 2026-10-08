package com.smccloud.hotdeath

import android.content.res.Configuration
import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.lang.reflect.Modifier

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class CardArtTest
{
	private data class Bucket(
			val name: String,
			val width: Int,
			val height: Int,
			val densityQualifier: String)

	private val buckets = listOf(
			Bucket("drawable-mdpi", 52, 80, "mdpi"),
			Bucket("drawable-hdpi", 77, 120, "hdpi"),
			Bucket("drawable-xlarge-mdpi", 77, 120, "xlarge-mdpi"),
			Bucket("drawable-xhdpi", 103, 160, "xhdpi"),
			Bucket("drawable-xlarge-hdpi", 103, 160, "xlarge-hdpi"))

	private fun getAllCardIds(): List<Int>
	{
		val cardClass = Card::class.java
		return cardClass.declaredFields
			.filter { 
				it.name.startsWith("ID_") && 
				Modifier.isPublic(it.modifiers) && 
				Modifier.isStatic(it.modifiers) &&
				it.type == Int::class.java 
			}
			.map { 
				it.getInt(null)
			}
			.sorted()
	}

	@Test
	fun everyCardIdHasAFaceInAllBuckets()
	{
		val activity = org.robolectric.Robolectric.buildActivity(GameActivity::class.java).get()
		val game = Game(activity, GameOptions(activity))
		val table = GameTable(activity, game, GameOptions(activity))
		table.measure(100, 100)
		table.layout(0, 0, 100, 100)

		val cardIds = getAllCardIds()
		assertEquals("Should have 81 card IDs", 81, cardIds.size)

		for (bucket in buckets)
		{
			val old = RuntimeEnvironment.getQualifiers()
			RuntimeEnvironment.setQualifiers(bucket.densityQualifier)
			for (id in cardIds)
			{
				val drawableId = table.getCardImageID(id)
				assertNotNull("Card ID $id missing drawable in ${bucket.name}", drawableId)
				val bmp = table.getCardBitmap(id)
				assertNotNull("Card ID $id missing bitmap in ${bucket.name}", bmp)
				// Skip size check of scaled bitmaps - they're scaled for display
			}
			RuntimeEnvironment.setQualifiers(old)
		}
	}

	@Test
	fun allReferencedDrawablesExistInAllBucketsWithCorrectDimensions()
	{
		val activity = org.robolectric.Robolectric.buildActivity(GameActivity::class.java).get()
		val game = Game(activity, GameOptions(activity))
		val table = GameTable(activity, game, GameOptions(activity))
		table.measure(100, 100)
		table.layout(0, 0, 100, 100)

		val cardIds = getAllCardIds()
		for (bucket in buckets)
		{
			val old = RuntimeEnvironment.getQualifiers()
			RuntimeEnvironment.setQualifiers(bucket.densityQualifier)
			val resources = RuntimeEnvironment.getApplication().resources
			for (id in cardIds)
			{
				val drawableId = table.getCardImageID(id)
				try
				{
					val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
					BitmapFactory.decodeResource(resources, drawableId, opts)
					if (opts.outWidth == -1 || opts.outHeight == -1)
					{
						fail("Cannot decode drawable $drawableId for card ID $id in ${bucket.name}")
					}
					assertEquals("Drawable for card ID $id has wrong width in ${bucket.name}", 
						bucket.width, opts.outWidth)
					assertEquals("Drawable for card ID $id has wrong height in ${bucket.name}", 
						bucket.height, opts.outHeight)
				}
				catch (e: Exception)
				{
					fail("Failed to read drawable $drawableId for card ID $id in ${bucket.name}: ${e.message}")
				}
			}
			RuntimeEnvironment.setQualifiers(old)
		}
	}
}

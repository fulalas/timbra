// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra

import androidx.media3.common.Player
import com.timbra.player.RepeatMode
import com.timbra.player.ShuffleMode
import com.timbra.player.cycleNext
import com.timbra.player.enumByName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayModesTest {

    @Test fun `shuffle cycles and wraps`() {
        assertEquals(ShuffleMode.CURRENT, ShuffleMode.OFF.cycleNext())
        assertEquals(ShuffleMode.ALL, ShuffleMode.CURRENT.cycleNext())
        assertEquals(ShuffleMode.OFF, ShuffleMode.ALL.cycleNext())
    }

    @Test fun `repeat cycles and wraps`() {
        assertEquals(RepeatMode.LIST, RepeatMode.OFF.cycleNext())
        assertEquals(RepeatMode.ADVANCE, RepeatMode.LIST.cycleNext())
        assertEquals(RepeatMode.SONG, RepeatMode.ADVANCE.cycleNext())
        assertEquals(RepeatMode.OFF, RepeatMode.SONG.cycleNext())
    }

    @Test fun `cycling all the way round returns to the start`() {
        var m = ShuffleMode.OFF
        repeat(ShuffleMode.entries.size) { m = m.cycleNext() }
        assertEquals(ShuffleMode.OFF, m)
    }

    @Test fun `enumByName round-trips every entry`() {
        // This is what persistence relies on: names are stable under reordering, ordinals are not.
        for (m in ShuffleMode.entries) assertEquals(m, enumByName(m.name, ShuffleMode.OFF))
        for (m in RepeatMode.entries) assertEquals(m, enumByName(m.name, RepeatMode.OFF))
    }

    @Test fun `enumByName falls back for an unknown or absent name`() {
        assertEquals(RepeatMode.LIST, enumByName("WAS_REMOVED", RepeatMode.LIST))
        assertEquals(RepeatMode.ADVANCE, enumByName(null, RepeatMode.ADVANCE))
        assertEquals(ShuffleMode.OFF, enumByName("", ShuffleMode.OFF))
    }

    @Test fun `a folder advance narrows Shuffle-All to Shuffle-Songs and leaves the rest alone`() {
        assertEquals(ShuffleMode.CURRENT, ShuffleMode.ALL.narrowedToFolder())
        assertEquals(ShuffleMode.CURRENT, ShuffleMode.CURRENT.narrowedToFolder())
        assertEquals(ShuffleMode.OFF, ShuffleMode.OFF.narrowedToFolder())
    }

    @Test fun `player shuffle is enabled for every mode except OFF`() {
        assertFalse(ShuffleMode.OFF.playerShuffleEnabled)
        assertTrue(ShuffleMode.CURRENT.playerShuffleEnabled)
        assertTrue(ShuffleMode.ALL.playerShuffleEnabled)
    }

    @Test fun `absent subtitles are null, not a zero resource id`() {
        // 0 is not a valid resource id: modelling absence in the type is what stops a caller
        // handing it to getString and getting a NotFoundException.
        assertNull(ShuffleMode.OFF.subtitleRes)
        assertNull(ShuffleMode.ALL.subtitleRes)
        assertNotNull(ShuffleMode.CURRENT.subtitleRes)
        assertNull(RepeatMode.OFF.subtitleRes)
        assertNull(RepeatMode.SONG.subtitleRes)
        assertNotNull(RepeatMode.LIST.subtitleRes)
        assertNotNull(RepeatMode.ADVANCE.subtitleRes)
        for (m in ShuffleMode.entries) assertTrue(m.titleRes != 0 && m.iconRes != 0)
        for (m in RepeatMode.entries) assertTrue(m.titleRes != 0 && m.iconRes != 0)
    }

    @Test fun `Advance-List drives the player with repeat OFF, so the app owns the step`() {
        assertEquals(Player.REPEAT_MODE_OFF, RepeatMode.ADVANCE.playerMode)
        assertEquals(Player.REPEAT_MODE_OFF, RepeatMode.OFF.playerMode)
        assertEquals(Player.REPEAT_MODE_ALL, RepeatMode.LIST.playerMode)
        assertEquals(Player.REPEAT_MODE_ONE, RepeatMode.SONG.playerMode)
    }
}

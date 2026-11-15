package com.example.carradio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RecognitionDraftTest {
    @Test
    fun lateIntermediateResultCannotReplaceFinalText() {
        val draft = RecognitionDraft()
        draft.accept("앞에 차", false)
        draft.accept("앞에 차량이 정차해 있습니다.", true)
        draft.accept("앞에", false)
        assertEquals("앞에 차량이 정차해 있습니다.", draft.forSave())
    }

    @Test
    fun emptyFinalResultKeepsTheIntermediateTextUnconfirmed() {
        val draft = RecognitionDraft()
        draft.accept("타이어에 문제가 있습니다.", false)
        draft.accept("  ", true)
        assertEquals("타이어에 문제가 있습니다.", draft.forSave())
        assertFalse(draft.completed)
    }

    @Test
    fun manualCorrectionCannotBeOverwrittenByRecognition() {
        val draft = RecognitionDraft()
        draft.accept("1차선", true)
        draft.edit("2차선입니다.")
        draft.accept("1차선입니다.", true)
        assertEquals("2차선입니다.", draft.forSave())
    }

    @Test
    fun blankTextCannotBeSavedAndLengthIsBounded() {
        val draft = RecognitionDraft()
        draft.edit(" \n ")
        assertNull(draft.forSave())
        draft.edit("가".repeat(250))
        assertEquals(200, draft.forSave()?.length)
    }
}

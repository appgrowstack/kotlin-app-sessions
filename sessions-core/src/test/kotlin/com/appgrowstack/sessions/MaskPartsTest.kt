package com.appgrowstack.sessions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A view of a fake hierarchy: what Android's views and Compose's nodes look like to the search for the parts to hide. */
private data class FakeView(
    override val shown: Boolean = true,
    override val hidesWhole: Boolean = false,
    override val bounds: SessionRect? = SessionRect(0.0, 0.0, 10.0, 10.0),
    override val children: List<SessionMaskNode> = emptyList(),
) : SessionMaskNode

private fun group(vararg children: SessionMaskNode) = FakeView(children = children.toList())
private fun label(r: SessionRect) = FakeView(bounds = r)
private fun password(r: SessionRect?) = FakeView(hidesWhole = true, bounds = r)

class MaskPartsTest {
    private val field = SessionRect(60.0, 900.0, 1050.0, 132.0)
    private val image = SessionPixelSize(1179, 2556)

    // ---- SC8: password fields are hidden without any mark from the app

    @Test
    fun aPasswordFieldIsHidden() {
        val root = group(label(SessionRect(60.0, 300.0, 1050.0, 90.0)), group(password(field)))
        assertEquals(listOf<SessionRect?>(field), SessionRules.partsToHide(root))
    }

    @Test
    fun plainViewsAreShown() {
        assertEquals(emptyList(), SessionRules.partsToHide(group(label(field), group(label(field)))))
    }

    @Test
    fun whatIsInsideAHiddenViewIsNotWalkedAgain() {
        val outer = FakeView(hidesWhole = true, bounds = field, children = listOf(password(SessionRect(70.0, 910.0, 10.0, 10.0))))
        assertEquals(listOf<SessionRect?>(field), SessionRules.partsToHide(group(outer)))
    }

    @Test
    fun aViewThatIsNotShownIsSkipped() {
        val gone = FakeView(shown = false, children = listOf(password(field)))
        assertEquals(emptyList(), SessionRules.partsToHide(group(gone)))
    }

    @Test
    fun aFieldWithNothingVisibleIsLeftOut() {
        assertEquals(emptyList(), SessionRules.partsToHide(group(password(SessionRect(0.0, 0.0, 0.0, 0.0)))))
    }

    @Test
    fun aFieldWhosePlaceIsUnknownDropsTheFrame() {
        assertEquals(listOf<SessionRect?>(null), SessionRules.partsToHide(group(password(null))))
        assertEquals(listOf<SessionRect?>(null), SessionRules.partsToHide(group(password(SessionRect(Double.NaN, 0.0, 10.0, 10.0)))))
        val plan = SessionRules.maskRects(1.0, image, SessionRules.partsToHide(group(password(null))))
        assertTrue(plan.drop)
        assertEquals(SessionBlankReason.MASKING_FAILED, plan.reason)
    }

    @Test
    fun tooManyViewsDropTheFrame() {
        var deep: SessionMaskNode = password(field)
        repeat(50) { deep = group(deep) }
        assertNull(SessionRules.partsToHide(deep, limit = 20).last())
        assertEquals(listOf<SessionRect?>(field), SessionRules.partsToHide(deep))
    }

    @Test
    fun aPlaceThatIsNotANumberIsNeverPaintedAsNothing() {
        val plan = SessionRules.maskRects(1.0, image, listOf(SessionRect(0.0, 0.0, Double.POSITIVE_INFINITY, 10.0)))
        assertTrue(plan.drop)
        assertEquals(SessionBlankReason.MASKING_FAILED, plan.reason)
    }

    // ---- SC8: the masking throwing, or taking over 16 ms, gives a blank frame (`masking_failed`)

    @Test
    fun findingThatThrowsDropsTheFrame() {
        val plan = SessionRules.maskRects(1.0, image, nowMs = { 0.0 }) { throw IllegalStateException("semantics owner gone") }
        assertTrue(plan.drop)
        assertEquals(SessionBlankReason.MASKING_FAILED, plan.reason)
        assertEquals(emptyList(), plan.rects)
    }

    @Test
    fun findingOverTheBudgetDropsTheFrame() {
        var clock = 1000.0
        val plan = SessionRules.maskRects(1.0, image, nowMs = { clock }) {
            clock += 16.5
            listOf(field)
        }
        assertTrue(plan.drop)
        assertEquals(SessionBlankReason.MASKING_FAILED, plan.reason)
    }

    @Test
    fun findingInTimePaintsTheParts() {
        var clock = 1000.0
        val plan = SessionRules.maskRects(1.0, image, nowMs = { clock }) {
            clock += 16.0
            listOf(field)
        }
        assertFalse(plan.drop)
        assertEquals(listOf(SessionPixelRect(60, 900, 1050, 132)), plan.rects)
    }

    // ---- What a password field is

    @Test
    fun passwordInputTypes() {
        // TYPE_CLASS_TEXT with TYPE_TEXT_VARIATION_PASSWORD, _VISIBLE_PASSWORD, _WEB_PASSWORD
        assertTrue(SessionRules.isPasswordInputType(0x81))
        assertTrue(SessionRules.isPasswordInputType(0x91))
        assertTrue(SessionRules.isPasswordInputType(0xe1))
        // TYPE_CLASS_NUMBER with TYPE_NUMBER_VARIATION_PASSWORD
        assertTrue(SessionRules.isPasswordInputType(0x12))
        // With flags (TYPE_TEXT_FLAG_NO_SUGGESTIONS) still a password
        assertTrue(SessionRules.isPasswordInputType(0x81 or 0x80000))
    }

    @Test
    fun otherInputTypesAreNotPasswords() {
        assertFalse(SessionRules.isPasswordInputType(0x1)) // plain text
        assertFalse(SessionRules.isPasswordInputType(0x21)) // email address
        assertFalse(SessionRules.isPasswordInputType(0x2)) // number
        assertFalse(SessionRules.isPasswordInputType(0x3)) // phone
        assertFalse(SessionRules.isPasswordInputType(0x10)) // TYPE_NUMBER_VARIATION_PASSWORD's bits without a class
        assertFalse(SessionRules.isPasswordInputType(0))
    }

    @Test
    fun passwordHints() {
        assertTrue(SessionRules.isPasswordHint("password"))
        assertTrue(SessionRules.isPasswordHint("newPassword"))
        assertFalse(SessionRules.isPasswordHint("username"))
        assertFalse(SessionRules.isPasswordHint("emailAddress"))
    }
}

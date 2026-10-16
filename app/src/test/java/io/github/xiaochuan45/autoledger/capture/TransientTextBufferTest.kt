package io.github.xiaochuan45.autoledger.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientTextBufferTest {
    @Test fun rejectsOversizedForeignTextBeforeCopyingIt() {
        val foreign = object : CharSequence {
            override val length = 10_000_000
            override fun get(index: Int): Char = 'x'
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
                error("oversized foreign text must not be copied, even partially")
            }
            override fun toString(): String = error("must not copy the entire foreign text")
        }
        val buffer = TransientTextBuffer()
        buffer.add(foreign)
        assertTrue(buffer.toList().isEmpty())
        assertTrue(buffer.incomplete)
    }

    @Test fun totalBudgetAppliesAcrossFields() {
        val buffer = TransientTextBuffer()
        buffer.add("a".repeat(3_000))
        buffer.add("b".repeat(3_000))
        buffer.add("unnecessary unrelated content")
        assertTrue(buffer.incomplete)
        assertTrue(buffer.toList().isEmpty())
    }

    @Test fun fragmentCountStopsLargeNotificationLineArrays() {
        val buffer = TransientTextBuffer()
        repeat(1_000) { buffer.add("fictional line $it") }
        assertTrue(buffer.incomplete)
        assertTrue(buffer.toList().isEmpty())
    }

    @Test fun duplicateFieldsAndBlankTextAreNotRetained() {
        val buffer = TransientTextBuffer()
        listOf(null, "", "   ", "  fictional receipt  ", "fictional receipt").forEach(buffer::add)
        assertEquals(listOf("fictional receipt"), buffer.toList())
        assertFalse(buffer.incomplete)
    }

    @Test fun exactBoundaryIsCompleteOnlyWhenNoEvidenceWasOmitted() {
        val characters = TransientTextBuffer()
        characters.add("x".repeat(4_000))
        assertFalse(characters.incomplete)
        assertEquals(4_000, characters.toList().single().length)
        characters.add("支付失败")
        assertTrue(characters.incomplete)
        assertTrue(characters.toList().isEmpty())

        val fragments = TransientTextBuffer()
        repeat(24) { fragments.add("fictional line $it") }
        assertFalse(fragments.incomplete)
        assertEquals(24, fragments.toList().size)
        fragments.add("支付失败")
        assertTrue(fragments.incomplete)
        assertTrue(fragments.toList().isEmpty())
    }

    @Test fun overBudgetSuccessPrefixCannotHideFailureSuffix() {
        val buffer = TransientTextBuffer()
        buffer.add("支付成功 支付金额23.50元" + "x".repeat(4_000) + "支付失败")
        assertTrue(buffer.incomplete)
        assertTrue(buffer.toList().isEmpty())
    }

    @Test fun verificationAndPasswordSamplesAreDiscardable() {
        listOf("验证码 000000", "请输入支付密码", "安全验证", "Verification Code 000000", "PASSWORD", "one-time code")
            .forEach { assertTrue(hasSensitiveText(it)) }
        assertFalse(hasSensitiveText("支付成功，实付 ¥23.50，虚构商户"))
        assertFalse(hasSensitiveText(null))
    }
}

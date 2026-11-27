package com.personalsecretary.app

import com.personalsecretary.app.voice.SpeechTextFormatter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SpeechTextFormatterTest {

    @Test
    fun formatsFinancialDatesAndRupeesIntoNaturalSpokenEnglish() {
        val today = LocalDate.of(2026, 11, 27)
        val raw = """
            **Verified Figures**
            Yesterday (2026-11-26) Spending Breakdown (Rs. 1,730.00 spent):
            • Mobile Topup: -Rs. 100.00 (Utilities & Subscriptions)
            • Mustang: -Rs. 350.00 (Tobacco & Alcohol)

            **Clara's Advice**
            Yesterday's spending was Rs. 1,730.00 against your Rs. 1,166.67/d cap.
            1. Keep today's spending within budget.
            2. Review your 7d average and SSF return (85%).
        """.trimIndent()

        val spoken = SpeechTextFormatter.formatForSpeech(raw, today)

        assertTrue("Expected natural date: $spoken", spoken.contains("November twenty sixth"))
        assertTrue("Expected spoken rupees without .00: $spoken", spoken.contains("one thousand seven hundred thirty rupees"))
        assertTrue("Expected spoken decimals & per day: $spoken", spoken.contains("one thousand one hundred sixty six point six seven rupees per day"))
        assertTrue("Expected spoken percent: $spoken", spoken.contains("eighty five percent"))
        assertTrue("Expected expanded SSF: $spoken", spoken.contains("S S F"))
        assertTrue("Expected expanded 7d: $spoken", spoken.contains("seven day"))
        assertFalse("Should not speak raw ISO date digits: $spoken", spoken.contains("2026-11-26"))
        assertFalse("Should not speak raw Rs. abbreviation: $spoken", spoken.contains("Rs."))
        assertFalse("Should not recite raw ledger bullet lines when Clara's Advice is present: $spoken", spoken.contains("Mobile Topup"))
        assertFalse("Should not leak raw digits to lexicon TTS: $spoken", Regex("\\d").containsMatchIn(spoken))

        val chunks = SpeechTextFormatter.prepareSpokenSentences(raw, today)
        assertTrue("Should produce at least 2 natural sentence chunks", chunks.size >= 2)
        assertTrue("All chunks should stay concise for low-latency streaming", chunks.all { it.length <= 220 })
    }
}

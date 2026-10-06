package org.example.service

import java.text.BreakIterator
import java.util.Locale

/**
 * Проверка «это один эмодзи». Принимает всё, что рисуется как одна картинка:
 * обычные 👍, с выбором цвета кожи 👍🏽, семьи и профессии через ZWJ 👨‍👩‍👧‍👦 🧑🏿‍🚀,
 * флаги 🇷🇺 🏴󠁧󠁢󠁳󠁣󠁴󠁿 🏳️‍🌈, клавиши 1️⃣ #️⃣, новые эмодзи из свежего Unicode.
 * Не принимает буквы, цифры, слова и несколько эмодзи подряд.
 *
 * Работает на свойствах Unicode из JDK 21 (Character.isEmoji/isExtendedPictographic)
 * и графемах BreakIterator (с JDK 20 он понимает ZWJ-последовательности).
 */
object EmojiText {
    const val MAX_LEN = 64

    /** Нормализованный эмодзи или null, если это не один эмодзи. */
    fun normalize(raw: String?): String? {
        val e = raw?.trim() ?: return null
        if (e.isEmpty() || e.length > MAX_LEN) return null
        if (graphemes(e) != 1) return null
        val cps = codePoints(e)
        var pictographic = false
        for (cp in cps) {
            val component = Character.isEmojiComponent(cp) || cp == 0xFE0F || cp == 0x200D || cp == 0x20E3 ||
                    cp in 0xE0020..0xE007F
            if (!Character.isEmoji(cp) && !component) return null
            if (Character.isExtendedPictographic(cp) || cp in 0x1F1E6..0x1F1FF || cp == 0x20E3) pictographic = true
        }
        if (!pictographic) return null
        // «❤» и «❤️» — одна реакция: одиночному символу текстового вида добавляем VS16
        if (cps.size == 1 && !Character.isEmojiPresentation(cps[0])) return e + "\uFE0F"
        return e
    }

    private fun codePoints(s: String): IntArray {
        val out = ArrayList<Int>()
        var i = 0
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            out += cp
            i += Character.charCount(cp)
        }
        return out.toIntArray()
    }

    fun graphemes(s: String): Int {
        val it = BreakIterator.getCharacterInstance(Locale.ROOT)
        it.setText(s)
        var n = 0
        it.first()
        while (it.next() != BreakIterator.DONE) n++
        return n
    }
}

package org.example.service

import java.util.concurrent.ConcurrentHashMap

/**
 * Крошечный кэш на ноде: значение живёт [ttlMs]. Для того, что одинаково для
 * всех и не обязано быть секунда-в-секунду (горячие теги, глобальная бегущая
 * строка, счётчики онлайна): вместо запроса на каждого смотрящего — один на
 * ноду раз в [ttlMs]. Одновременный промах может посчитать значение дважды —
 * это дешевле блокировки.
 */
class TtlCache<K : Any, V : Any>(private val ttlMs: Long, private val maxSize: Int = 1000) {
    private class Entry<V>(val at: Long, val value: V)

    private val map = ConcurrentHashMap<K, Entry<V>>()

    fun get(key: K, load: () -> V): V {
        val now = System.currentTimeMillis()
        map[key]?.let { if (now - it.at < ttlMs) return it.value }
        val v = load()
        if (map.size >= maxSize) map.clear()
        map[key] = Entry(now, v)
        return v
    }
}
